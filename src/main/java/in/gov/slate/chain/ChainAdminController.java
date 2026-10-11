package in.gov.slate.chain;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;

@RestController
@RequestMapping("/api/chain/reconcile")
public class ChainAdminController {

    private final ChainReconciliationService reconciliation;

    public ChainAdminController(ChainReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    private static void requireAdmin() {
        CurrentUser user = CurrentUser.require();
        if (!user.hasRole("STATE_ADMIN") && !user.hasRole("CENTRAL_ADMIN")) {
            throw ApiException.forbidden("Only administrators can run chain reconciliation");
        }
    }

    @PostMapping
    public Map<String, Object> run() {
        requireAdmin();
        return reconciliation.run("MANUAL");
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        requireAdmin();
        return reconciliation.latest();
    }
}
