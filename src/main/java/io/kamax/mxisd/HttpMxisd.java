/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2018 Kamax Sarl
 *
 * https://www.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd;

import io.kamax.mxisd.config.MatrixConfig;
import io.kamax.mxisd.config.MxisdConfig;
import io.kamax.mxisd.config.PolicyConfig;
import io.kamax.mxisd.http.undertow.handler.ApiHandler;
import io.kamax.mxisd.http.undertow.handler.AuthorizationHandler;
import io.kamax.mxisd.http.undertow.handler.CheckTermsHandler;
import io.kamax.mxisd.http.undertow.handler.InternalInfoHandler;
import io.kamax.mxisd.http.undertow.handler.OptionsHandler;
import io.kamax.mxisd.http.undertow.handler.SaneHandler;
import io.kamax.mxisd.http.undertow.handler.as.v1.AsNotFoundHandler;
import io.kamax.mxisd.http.undertow.handler.as.v1.AsTransactionHandler;
import io.kamax.mxisd.http.undertow.handler.as.v1.AsUserHandler;
import io.kamax.mxisd.http.undertow.handler.auth.RestAuthHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v1.LoginGetHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v1.LoginHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v1.LoginPostHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v2.AccountGetUserInfoHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v2.AccountLogoutHandler;
import io.kamax.mxisd.http.undertow.handler.auth.v2.AccountRegisterHandler;
import io.kamax.mxisd.http.undertow.handler.directory.v1.UserDirectorySearchHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.EphemeralKeyIsValidHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.HelloHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.KeyGetHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.RegularKeyIsValidHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionStartHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionTpidBindHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionTpidGetValidatedHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionTpidUnbindHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionValidationGetHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SessionValidationPostHandler;
import io.kamax.mxisd.http.undertow.handler.identity.share.SignEd25519Handler;
import io.kamax.mxisd.http.undertow.handler.identity.share.StoreInviteHandler;
import io.kamax.mxisd.http.undertow.handler.identity.v1.BulkLookupHandler;
import io.kamax.mxisd.http.undertow.handler.identity.v1.SingleLookupHandler;
import io.kamax.mxisd.http.undertow.handler.identity.v2.HashDetailsHandler;
import io.kamax.mxisd.http.undertow.handler.identity.v2.HashLookupHandler;
import io.kamax.mxisd.http.undertow.handler.invite.v1.RoomInviteHandler;
import io.kamax.mxisd.http.undertow.handler.profile.v1.InternalProfileHandler;
import io.kamax.mxisd.http.undertow.handler.profile.v1.ProfileHandler;
import io.kamax.mxisd.http.undertow.handler.register.v1.Register3pidRequestTokenHandler;
import io.kamax.mxisd.http.undertow.handler.status.StatusHandler;
import io.kamax.mxisd.http.undertow.handler.status.VersionHandler;
import io.kamax.mxisd.http.undertow.handler.term.v2.AcceptTermsHandler;
import io.kamax.mxisd.http.undertow.handler.term.v2.GetTermsHandler;
import io.kamax.mxisd.matrix.IdentityServiceAPI;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class HttpMxisd {

    // Core
    private Mxisd m;

    // I/O
    private Undertow httpSrv;

    static {
        // Used in XNIO package, dependency of Undertow
        // We switch to slf4j
        System.setProperty("org.jboss.logging.provider", "slf4j");
    }

    public HttpMxisd(MxisdConfig cfg) {
        m = new Mxisd(cfg);
    }

    public void start() {
        m.start();

        HttpHandler asUserHandler = SaneHandler.around(new AsUserHandler(m.getAs()));
        HttpHandler asTxnHandler = SaneHandler.around(new AsTransactionHandler(m.getAs()));
        HttpHandler asNotFoundHandler = SaneHandler.around(new AsNotFoundHandler(m.getAs()));

        final RoutingHandler handler = Handlers.routing()
            .add("OPTIONS", "/**", SaneHandler.around(new OptionsHandler()))

            // Status endpoints
            .get(StatusHandler.Path, SaneHandler.around(new StatusHandler()))
            .get(VersionHandler.Path, SaneHandler.around(new VersionHandler()))

            // Authentication endpoints
            .get(LoginHandler.Path, SaneHandler.around(new LoginGetHandler(m.getAuth(), m.getHttpClient())))
            .post(LoginHandler.Path, SaneHandler.around(new LoginPostHandler(m.getAuth())))
            .post(RestAuthHandler.Path, SaneHandler.around(new RestAuthHandler(m.getAuth())))

            // Directory endpoints
            .post(UserDirectorySearchHandler.Path, SaneHandler.around(new UserDirectorySearchHandler(m.getDirectory())))

            // Profile endpoints
            .get(ProfileHandler.Path, SaneHandler.around(new ProfileHandler(m.getProfile())))
            .get(InternalProfileHandler.Path, SaneHandler.around(new InternalProfileHandler(m.getProfile())))

            // Registration endpoints
            .post(Register3pidRequestTokenHandler.Path,
                SaneHandler.around(new Register3pidRequestTokenHandler(m.getReg(), m.getClientDns(), m.getHttpClient())))

            // Invite endpoints
            .post(RoomInviteHandler.Path, SaneHandler.around(new RoomInviteHandler(m.getHttpClient(), m.getClientDns(), m.getInvite())))

            // Application Service endpoints
            .get(AsUserHandler.Path, asUserHandler)
            .get("/_matrix/app/v1/rooms/**", asNotFoundHandler)
            .put(AsTransactionHandler.Path, asTxnHandler)

            .get("/users/{" + AsUserHandler.ID + "}", asUserHandler) // Legacy endpoint
            .get("/rooms/**", asNotFoundHandler) // Legacy endpoint
            .put("/transactions/{" + AsTransactionHandler.ID + "}", asTxnHandler) // Legacy endpoint

            // Banned endpoints
            .get(InternalInfoHandler.Path, SaneHandler.around(new InternalInfoHandler()));
        keyEndpoints(handler);
        identityEndpoints(handler);
        termsEndpoints(handler);
        hashEndpoints(handler);
        accountEndpoints(handler);
        httpSrv = Undertow.builder().addHttpListener(m.getConfig().getServer().getPort(), "0.0.0.0").setHandler(handler).build();

        httpSrv.start();
    }

    public void stop() {
        // Because it might have never been initialized if an exception is thrown early
        if (Objects.nonNull(httpSrv)) {
            httpSrv.stop();
        }

        m.stop();
    }

    private void keyEndpoints(RoutingHandler routingHandler) {
        addEndpoints(routingHandler, Methods.GET, false,
            new KeyGetHandler(m.getKeyManager()),
            new RegularKeyIsValidHandler(m.getKeyManager()),
            new EphemeralKeyIsValidHandler(m.getKeyManager())
        );
    }

    private void identityEndpoints(RoutingHandler routingHandler) {
        // Legacy v1
        routingHandler.get(SingleLookupHandler.Path, sane(new SingleLookupHandler(m.getConfig(), m.getIdentity(), m.getSign())));
        routingHandler.post(BulkLookupHandler.Path, sane(new BulkLookupHandler(m.getIdentity())));

        addEndpoints(routingHandler, Methods.GET, false, new HelloHandler());

        addEndpoints(routingHandler, Methods.GET, true,
            new SessionValidationGetHandler(m.getSession(), m.getConfig()),
            new SessionTpidGetValidatedHandler(m.getSession())
        );
        addEndpoints(routingHandler, Methods.POST, true,
            new StoreInviteHandler(m.getConfig().getServer(), m.getInvite(), m.getKeyManager()),
            new SessionStartHandler(m.getSession()),
            new SessionValidationPostHandler(m.getSession()),
            new SessionTpidBindHandler(m.getSession(), m.getInvite(), m.getSign()),
            new SessionTpidUnbindHandler(m.getSession()),
            new SignEd25519Handler(m.getConfig(), m.getInvite(), m.getSign())
        );
    }

    private void accountEndpoints(RoutingHandler routingHandler) {
        routingHandler.post(AccountRegisterHandler.Path, SaneHandler.around(new AccountRegisterHandler(m.getAccMgr())));
        wrapWithTokenAndAuthorizationHandlers(routingHandler, Methods.GET, sane(new AccountGetUserInfoHandler(m.getAccMgr())),
            AccountGetUserInfoHandler.Path, true);
        wrapWithTokenAndAuthorizationHandlers(routingHandler, Methods.GET, sane(new AccountLogoutHandler(m.getAccMgr())),
            AccountLogoutHandler.Path, true);
    }

    private void termsEndpoints(RoutingHandler routingHandler) {
        routingHandler.get(GetTermsHandler.PATH, new GetTermsHandler(m.getConfig().getPolicy()));
        wrapWithTokenAndAuthorizationHandlers(routingHandler, Methods.POST, sane(new AcceptTermsHandler(m.getAccMgr())),
            AcceptTermsHandler.PATH, true);
    }

    private void hashEndpoints(RoutingHandler routingHandler) {
        wrapWithTokenAndAuthorizationHandlers(routingHandler, Methods.GET, sane(new HashDetailsHandler(m.getHashManager())),
            HashDetailsHandler.PATH, true);
        wrapWithTokenAndAuthorizationHandlers(routingHandler, Methods.POST,
            sane(new HashLookupHandler(m.getIdentity(), m.getHashManager())), HashLookupHandler.Path, true);
    }

    private void addEndpoints(RoutingHandler routingHandler, HttpString method, boolean useAuthorization, ApiHandler... handlers) {
        for (ApiHandler handler : handlers) {
            attachHandler(routingHandler, method, handler, useAuthorization, sane(handler));
        }
    }

    private void attachHandler(RoutingHandler routingHandler, HttpString method, ApiHandler apiHandler, boolean useAuthorization,
                               HttpHandler httpHandler) {
        MatrixConfig matrixConfig = m.getConfig().getMatrix();
        if (matrixConfig.isV1()) {
            routingHandler.add(method, apiHandler.getPath(IdentityServiceAPI.V1), httpHandler);
        }
        if (matrixConfig.isV2()) {
            String path = apiHandler.getPath(IdentityServiceAPI.V2);
            wrapWithTokenAndAuthorizationHandlers(routingHandler, method, httpHandler, path, useAuthorization);
        }
    }

    private void wrapWithTokenAndAuthorizationHandlers(RoutingHandler routingHandler, HttpString method, HttpHandler httpHandler,
                                                       String url, boolean useAuthorization) {
        List<PolicyConfig.PolicyObject> policyObjects = getPolicyObjects(url);
        HttpHandler wrappedHandler;
        if (useAuthorization) {
            wrappedHandler = policyObjects.isEmpty() ? httpHandler : CheckTermsHandler.around(m.getAccMgr(), httpHandler, policyObjects);
            wrappedHandler = AuthorizationHandler.around(m.getAccMgr(), wrappedHandler);
        } else {
            wrappedHandler = httpHandler;
        }
        routingHandler.add(method, url, wrappedHandler);
    }

    @NotNull
    private List<PolicyConfig.PolicyObject> getPolicyObjects(String url) {
        PolicyConfig policyConfig = m.getConfig().getPolicy();
        List<PolicyConfig.PolicyObject> policies = new ArrayList<>();
        if (!policyConfig.getPolicies().isEmpty()) {
            for (PolicyConfig.PolicyObject policy : policyConfig.getPolicies().values()) {
                for (Pattern pattern : policy.getPatterns()) {
                    if (pattern.matcher(url).matches()) {
                        policies.add(policy);
                    }
                }
            }
        }
        return policies;
    }

    private HttpHandler sane(HttpHandler httpHandler) {
        return SaneHandler.around(httpHandler);
    }
}
