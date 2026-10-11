// SPDX-License-Identifier: UNLICENSED
pragma solidity 0.8.24;

/// SLATE property token anchor. Stores only token refs, state hashes and deed hashes:
/// no owner names or personal data. Entries are append-only; a correction is a new state.
contract SlatePropertyToken {
    address public admin;
    mapping(address => bool) public writers;

    struct TokenState {
        bytes32 stateHash;
        uint64 recordedAt;
        uint64 blockNumber;
    }

    mapping(bytes32 => TokenState[]) private states;
    mapping(bytes32 => mapping(bytes32 => bool)) private recorded;
    mapping(bytes32 => bytes32) private deeds;

    event TokenStateRecorded(bytes32 indexed tokenKey, string tokenRef, uint256 index, bytes32 stateHash);
    event DeedRecorded(bytes32 indexed documentKey, string documentNo, bytes32 deedHash);
    event WriterChanged(address writer, bool allowed);

    modifier onlyWriter() {
        require(writers[msg.sender], "not a SLATE writer");
        _;
    }

    constructor() {
        admin = msg.sender;
        writers[msg.sender] = true;
    }

    function setWriter(address writer, bool allowed) external {
        require(msg.sender == admin, "not admin");
        writers[writer] = allowed;
        emit WriterChanged(writer, allowed);
    }

    /// Signature kept as (uint256, bytes32, string) to match the existing SLATE anchor call.
    function recordTokenState(uint256, bytes32 stateHash, string calldata tokenRef) external onlyWriter {
        bytes32 key = keccak256(bytes(tokenRef));
        require(!recorded[key][stateHash], "state already recorded");
        recorded[key][stateHash] = true;
        states[key].push(TokenState(stateHash, uint64(block.timestamp), uint64(block.number)));
        emit TokenStateRecorded(key, tokenRef, states[key].length - 1, stateHash);
    }

    function recordDeed(string calldata documentNo, bytes32 deedHash) external onlyWriter {
        bytes32 key = keccak256(bytes(documentNo));
        require(deeds[key] == bytes32(0), "deed already recorded");
        deeds[key] = deedHash;
        emit DeedRecorded(key, documentNo, deedHash);
    }

    function hasState(string calldata tokenRef, bytes32 stateHash) external view returns (bool) {
        return recorded[keccak256(bytes(tokenRef))][stateHash];
    }

    function stateCount(string calldata tokenRef) external view returns (uint256) {
        return states[keccak256(bytes(tokenRef))].length;
    }

    function latestState(string calldata tokenRef) external view returns (bytes32 stateHash, uint256 blockNumber) {
        TokenState[] storage list = states[keccak256(bytes(tokenRef))];
        if (list.length == 0) {
            return (bytes32(0), 0);
        }
        TokenState storage last = list[list.length - 1];
        return (last.stateHash, last.blockNumber);
    }

    function deedOf(string calldata documentNo) external view returns (bytes32) {
        return deeds[keccak256(bytes(documentNo))];
    }
}
