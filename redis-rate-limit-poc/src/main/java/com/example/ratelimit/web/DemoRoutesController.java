package com.example.ratelimit.web;

import com.example.ratelimit.web.DemoRouteCatalog.Catalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only demo catalog for the console's request demo.
 *
 * <p>Lives under {@code /api/admin/**} because every entry carries the policies that limit it, and
 * policy details are administrator data.
 */
@RestController
@RequestMapping("/api/admin/rate-limit")
public class DemoRoutesController {

    private final DemoRouteCatalog catalog;

    public DemoRoutesController(DemoRouteCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/demo-routes")
    public Catalog demoRoutes() {
        return catalog.catalog();
    }
}