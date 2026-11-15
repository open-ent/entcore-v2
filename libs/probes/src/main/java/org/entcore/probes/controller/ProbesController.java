package org.entcore.probes.controller;

import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

public class ProbesController extends BaseController {



    @Get("/dev/null")
    @SecuredAction(value = "/dev/null", type = ActionType.AUTHENTICATED)
    public void noop(final HttpServerRequest request) {
        Renders.renderJson(request, new JsonObject().put("ok", true));
    }
}
