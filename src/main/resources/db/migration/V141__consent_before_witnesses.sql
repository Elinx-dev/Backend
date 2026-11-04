-- Buyer details and Aadhaar consent come before witnesses, so requesting consent no longer
-- needs the witnesses; rule checks refuse to run until the minimum witnesses are saved.
UPDATE cfg.workflow_transition
   SET guard_expr = 'partiesComplete'
 WHERE action_code = 'REQUEST_CONSENT'
   AND guard_expr = 'partiesAndWitnessesComplete';
