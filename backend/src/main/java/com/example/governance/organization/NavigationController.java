package com.example.governance.organization;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NavigationController {
    private final OrganizationService organization;
    public NavigationController(OrganizationService organization) { this.organization = organization; }
    @GetMapping("/api/navigation") List<OrganizationService.Menu> navigation(Principal principal) { return organization.navigation(principal.getName()); }
}
