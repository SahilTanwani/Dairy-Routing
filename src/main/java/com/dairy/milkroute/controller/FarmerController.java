package com.dairy.milkroute.controller;

import com.dairy.milkroute.dto.response.CollectionRecordResponse;
import com.dairy.milkroute.dto.response.FarmerStatusResponse;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.service.FarmerQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The farmer-facing endpoint: where is my tanker, and what did you take from me.
 *
 * <p>The one a reviewer should try first, because it is where every other part of the system
 * either pays off or is exposed. The spoilage model, the routing, the coverage decisions and
 * the event replay all exist so that this can answer honestly.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/farmers")
public class FarmerController {

    private final FarmerQueryService farmers;

    public FarmerController(FarmerQueryService farmers) {
        this.farmers = farmers;
    }

    /**
     * One plain sentence about today's collection.
     *
     * <p>Unauthenticated on purpose: this has to be usable from a feature phone by someone
     * who has never logged into anything. That is also why an unknown code returns a generic
     * 404 — an endpoint anyone can call must not become a way to enumerate the membership.
     */
    @GetMapping("/{farmerCode}/tanker-status")
    public FarmerStatusResponse tankerStatus(
            @PathVariable String farmerCode,
            @RequestParam(defaultValue = "MORNING") String session) {
        return farmers.tankerStatus(farmerCode, Session.valueOf(session.toUpperCase()));
    }

    /** His own milk records, newest first. Each farmer sees only their own. */
    @GetMapping("/{farmerCode}/collections")
    public List<CollectionRecordResponse> collections(@PathVariable String farmerCode) {
        return farmers.collections(farmerCode);
    }
}
