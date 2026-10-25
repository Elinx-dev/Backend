package in.gov.slate.chain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.CurrentUser;

/** Live Hyperledger Fabric network status for the application sidebar. */
@RestController
@RequestMapping("/api/chain")
public class ChainStatusController {

    private final ChainService chain;

    public ChainStatusController(ChainService chain) {
        this.chain = chain;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        CurrentUser.require();
        FabricLedger ledger = chain.ledger();
        boolean connected = chain.connected(null);
        List<Map<String, Object>> peers = new ArrayList<>();
        long height = -1;
        for (FabricLedger.Org org : FabricLedger.Org.values()) {
            long h = connected ? ledger.height(org) : -1;
            height = Math.max(height, h);
            peers.add(Map.of("org", org.mspId(), "up", h >= 0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", "Hyperledger Fabric");
        out.put("channel", ledger.channel());
        out.put("chaincode", ledger.chaincode());
        out.put("connected", connected);
        out.put("blockHeight", height >= 0 ? height : null);
        out.put("peersUp", peers.stream().filter(p -> Boolean.TRUE.equals(p.get("up"))).count());
        out.put("peersTotal", peers.size());
        out.put("peers", peers);
        return out;
    }
}
