package de.pemonline.api;
import de.pemonline.dashboard.DashboardService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }
    @GetMapping public DashboardService.Uebersicht laden() { return service.laden(); }
}
