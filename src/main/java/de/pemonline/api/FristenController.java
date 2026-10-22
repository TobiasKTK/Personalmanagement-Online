package de.pemonline.api;

import de.pemonline.fristen.FristenService;
import static de.pemonline.fristen.FristenModel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/fristen")
public class FristenController {
    private final FristenService service;
    public FristenController(FristenService service) { this.service = service; }
    @GetMapping public List<Frist> liste() { return service.liste(); }
    @GetMapping("/zustaendige") public List<Zustaendig> zustaendige() { return service.zustaendige(); }
    @PostMapping("/abgleich") @ResponseStatus(HttpStatus.NO_CONTENT) public void abgleichen() { service.abgleichen(); }
    @PutMapping("/{id}") public Frist speichern(@PathVariable @Positive long id, @Valid @RequestBody Eingabe e) { return service.speichern(id, e); }
}
