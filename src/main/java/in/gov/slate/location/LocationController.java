package in.gov.slate.location;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.CurrentUser;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locations;

    public LocationController(LocationService locations) {
        this.locations = locations;
    }

    @GetMapping("/districts")
    public List<Map<String, Object>> districts(@RequestParam(required = false) String stateCode) {
        return locations.districts(resolveState(stateCode));
    }

    @GetMapping("/sub-registrar-offices")
    public List<Map<String, Object>> subRegistrarOffices(@RequestParam(required = false) String stateCode,
                                                         @RequestParam String districtCode) {
        return locations.subRegistrarOffices(resolveState(stateCode), districtCode);
    }

    @GetMapping("/taluks")
    public List<Map<String, Object>> taluks(@RequestParam(required = false) String stateCode,
                                            @RequestParam String districtCode,
                                            @RequestParam String sroCode) {
        return locations.taluks(resolveState(stateCode), districtCode, sroCode);
    }

    @GetMapping("/revenue-villages")
    public List<Map<String, Object>> revenueVillages(@RequestParam(required = false) String stateCode,
                                                     @RequestParam String districtCode,
                                                     @RequestParam String sroCode,
                                                     @RequestParam String talukCode) {
        return locations.revenueVillages(resolveState(stateCode), districtCode, sroCode, talukCode);
    }

    private static String resolveState(String stateCode) {
        return stateCode == null || stateCode.isBlank() ? CurrentUser.require().stateCode() : stateCode;
    }
}
