/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2012-2013 ForgeRock Inc.
 */
package org.forgerock.openam.forgerockrest;

import com.iplanet.sso.SSOToken;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.OrganizationConfigManager;
import com.sun.identity.sm.SMSException;
import org.apache.commons.lang.StringUtils;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.*;
import org.jvnet.wom.impl.util.Iterators;
import org.omg.CosNaming.NamingContextPackage.CannotProceed;
import org.forgerock.openam.dashboard.DashboardResource;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.util.*;

import static org.forgerock.json.resource.RoutingMode.EQUALS;

/**
 * A simple {@code Map} based collection resource provider.
 */
public final class RestDispatcher {

    public static Debug debug = Debug.getInstance("frRest");

    /* Endpoints for forgerock-rest */
    final static private String REALMS = "/realms";
    final static private String USERS = "/users";
    final static private String GROUPS = "/groups";
    final static private String AGENTS = "/agents";
    final static private String DASHBOARD = "/dashboard";

    private static RestDispatcher instance = null;
    private RequestHandler handler = null;
    private ConnectionFactory factory = null;

    final Router router = new Router();

    private RestDispatcher() {

    }

    public final static RestDispatcher getInstance() {
        if (instance == null) instance = new RestDispatcher();
        return instance;
    }

    private static Set<String> getEndpointList() {
        Set<String> endpoints = new HashSet<String>(2);
        endpoints.add(USERS);
        endpoints.add(GROUPS);
        endpoints.add(AGENTS);
        endpoints.add(REALMS);
        endpoints.add(DASHBOARD);
        return endpoints;
    }

    private void callConfigClass(String className, Router router) {
        // Check for configured connection factory class first.
        if (className != null) {
            final ClassLoader cl = this.getClass().getClassLoader();
            try {
                final Class<?> cls = Class.forName(className, true, cl);
                // Try method which accepts ServletConfig.
                final Method factoryMethod = cls.getMethod("initDispatcher", Router.class);

                factoryMethod.invoke(null, router);
                return;
            } catch (final Exception e) {
            }
        }

    }

    /**
     * Returns a request handler which will handle all requests to a realm,
     * including sub-realms, users, and groups.
     *
     * @param path
     *            The realm.
     * @return A request handler which will handle all requests to a realm,
     *         including sub-realms, users, and groups.
     */
    private static RequestHandler realm(Map parsedDetails, final String path) {
        final Router router = new Router();
        String endpoint =  (String)parsedDetails.get("resourceName");
        String realmPath = (String)parsedDetails.get("realmPath");

        if (endpoint.equalsIgnoreCase(USERS)) {
            router.addRoute(endpoint,new IdentityResource("user", realmPath));
            router.addRoute(RoutingMode.STARTS_WITH, "/{user}", subrealms(parsedDetails,path));
        } else if (endpoint.equalsIgnoreCase(AGENTS)) {
            router.addRoute(endpoint,new IdentityResource("agent", realmPath));
            router.addRoute(RoutingMode.STARTS_WITH, "/{agent}", subrealms(parsedDetails,path));
        } else if (endpoint.equalsIgnoreCase(GROUPS)) {
            router.addRoute(endpoint,new IdentityResource("group", realmPath));
            router.addRoute(RoutingMode.STARTS_WITH, "/{group}", subrealms(parsedDetails,path));
        } else if (endpoint.equalsIgnoreCase(REALMS)) {
            router.addRoute(endpoint,new RealmResource(realmPath));
            router.addRoute(RoutingMode.STARTS_WITH, "/{realm}", subrealms(parsedDetails,path));
        } else if(endpoint.equalsIgnoreCase(DASHBOARD)){
            router.addRoute(endpoint,new DashboardResource());
        }
        return router;
    }

    /**
     * Build the initial dispatcher.
     * This is a separate method so that we can modify the dispatching
     * dynamically
     */
    public ConnectionFactory buildConnectionFactory(ServletConfig config) throws ResourceException {
        String roots = config.getInitParameter("rootContexts");

        if (roots == null) {
            throw new ServiceUnavailableException();
        }
        if (roots != null) {
            String[] initClasses = config.getInitParameter("rootContexts").split(","); // not really much to do

            for (String ctx : initClasses) {
                callConfigClass(ctx.trim(), router);
            }
        }
        factory = Resources.newInternalConnectionFactory(new RequestHandler() {
            public void handleAction(ServerContext serverContext, ActionRequest actionRequest, ResultHandler<JsonValue> jsonValueResultHandler) {
                //To change body of implemented methods use File | Settings | File Templates.
                jsonValueResultHandler.handleResult(new JsonValue(new HashMap<String, Object>()));
            }

            public void handleCreate(ServerContext serverContext, CreateRequest createRequest, ResultHandler<Resource> resourceResultHandler) {
                try{
                    Map parsedDetails = getRequestDetails(createRequest.getResourceName());
                    final RequestHandler rootRealm = realm(parsedDetails, createRequest.getResourceName());
                    rootRealm.handleCreate(serverContext, createRequest, resourceResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    resourceResultHandler.handleError(nfe);
                }
            }

            public void handleDelete(ServerContext serverContext, DeleteRequest deleteRequest, ResultHandler<Resource> resourceResultHandler) {
                try{
                    Map parsedDetails = getRequestDetails(deleteRequest.getResourceName());
                    final RequestHandler rootRealm = realm(parsedDetails, deleteRequest.getResourceName());
                    rootRealm.handleDelete(serverContext, deleteRequest, resourceResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    resourceResultHandler.handleError(nfe);
                }
            }

            public void handlePatch(ServerContext serverContext, PatchRequest patchRequest, ResultHandler<Resource> resourceResultHandler) {
                try{
                    Map parsedDetails = getRequestDetails(patchRequest.getResourceName());
                    final RequestHandler rootRealm = realm(parsedDetails, patchRequest.getResourceName());
                    rootRealm.handlePatch(serverContext, patchRequest, resourceResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    resourceResultHandler.handleError(nfe);
                }
            }

            public void handleQuery(ServerContext serverContext, QueryRequest queryRequest, QueryResultHandler queryResultHandler) {
                try{
                    Map parsedDetails = getRequestDetails(queryRequest.getResourceName());
                    final RequestHandler rootRealm = realm(parsedDetails, queryRequest.getResourceName());
                    rootRealm.handleQuery(serverContext, queryRequest, queryResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    queryResultHandler.handleError(nfe);
                }
            }

            public void handleRead(ServerContext serverContext, ReadRequest readRequest, ResultHandler<Resource> resourceResultHandler) {

                String realmPath = null;
                String endpoint = null;
                String resourceId = null;
                try{
                    Map parsedDetails = getRequestDetails(readRequest.getResourceName());
                    realmPath = (String)parsedDetails.get("realmPath");
                    endpoint = (String)parsedDetails.get("resourceName");
                    resourceId = (String)parsedDetails.get("resourceId");
                    if(null == realmPath || null == endpoint ){
                        resourceResultHandler.handleError(new PermanentException(404, "Cannot create collection.", null));
                    }
                    final RequestHandler rootRealm = realm(parsedDetails, readRequest.getResourceName());
                    rootRealm.handleRead(serverContext, readRequest, resourceResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    resourceResultHandler.handleError(nfe);
                }
            }

            public void handleUpdate(ServerContext serverContext, UpdateRequest updateRequest, ResultHandler<Resource> resourceResultHandler) {
                try{
                    Map parsedDetails = getRequestDetails(updateRequest.getResourceName());
                    final RequestHandler rootRealm = realm(parsedDetails, updateRequest.getResourceName());
                    rootRealm.handleUpdate(serverContext, updateRequest, resourceResultHandler);
                } catch( NotFoundException nfe) {
                    // URL not valid request
                    resourceResultHandler.handleError(nfe);
                }
            }
        });
        return factory;
    }

    public static ConnectionFactory getConnectionFactory(ServletConfig config) throws ServletException {
        try {
            return getInstance().buildConnectionFactory(config);
        } catch (final Exception e) {
            throw new ServletException(e);
        }
    }

    /**
     * Returns a request handler which will handle requests to a sub-realm.
     *
     * @param parentPath
     *            The parent realm.
     * @return A request handler which will handle requests to a sub-realm.
     */
    private static RequestHandler subrealms(final Map parsedDetails,final String parentPath) {
        return new RequestHandler() {

            public void handleAction(final ServerContext context, final ActionRequest request,
                                     final ResultHandler<JsonValue> handler) {
                subrealm(parentPath, context).handleAction(context, request, handler);
            }

            public void handleCreate(final ServerContext context, final CreateRequest request,
                                     final ResultHandler<Resource> handler) {
                subrealm(parentPath, context).handleCreate(context, request, handler);
            }

            public void handleDelete(final ServerContext context, final DeleteRequest request,
                                     final ResultHandler<Resource> handler) {
                subrealm(parentPath, context).handleDelete(context, request, handler);
            }

            public void handlePatch(final ServerContext context, final PatchRequest request,
                                    final ResultHandler<Resource> handler) {
                subrealm(parentPath, context).handlePatch(context, request, handler);
            }

            public void handleQuery(final ServerContext context, final QueryRequest request,
                                    final QueryResultHandler handler) {
                subrealm(parentPath, context).handleQuery(context, request, handler);
            }

            public void handleRead(final ServerContext context, final ReadRequest request,
                                   final ResultHandler<Resource> handler) {
                subrealm(parentPath, context).handleRead(context, request, handler);
            }

            public void handleUpdate(final ServerContext context, final UpdateRequest request,
                                     final ResultHandler<Resource> handler) {
                subrealm(parentPath, context).handleUpdate(context, request, handler);
            }

            private RequestHandler subrealm(final String parentPath,
                                            final ServerContext context) {
                return realm(parsedDetails,parentPath);
            }
        };
    }

    /**
     * Create an amAdmin SSOToken
     *
     * @return SSOToken adminSSOtoken
     */
    private SSOToken getSSOToken() {
        return (SSOToken) AccessController.doPrivileged(AdminTokenAction.getInstance());
    }

    private boolean checkValidEndpoint(String token) {
        Set<String> endPoints = getEndpointList();
        if (endPoints.contains(token)) {
            return true;
        }
        return false;
    }

    /**
     * Parse Realm Path, Resource Name, and Resource ID
     *
     * @return Map containing realmPath, resourceName, and resourceID
     * @throws NotFoundException when configuration manager cannot retrieve a realm
     */
    private Map<String,String> getRequestDetails(String resourceName) throws NotFoundException {
        Map<String,String> details = new HashMap<String,String>(3);
        if (StringUtils.isBlank(resourceName)) {
            return null;
        }
        StringTokenizer tokenizer = new StringTokenizer(resourceName.trim(), "/", false);
        boolean topLevel = true;
        String lastNonBlank = null;
        String lastNonBlankID = null;
        String tmp = null;
        StringBuilder realmPath = new StringBuilder("/"); //fqdn path to resource
        StringBuilder resourceID = null; //resource id
        StringBuilder endpoint = null; //defined endpoint

        OrganizationConfigManager ocm = null;

        try {
            ocm = new OrganizationConfigManager(getSSOToken(),realmPath.toString());
        } catch (SMSException smse){
            throw new NotFoundException(smse.getMessage(), smse);
        }
        while (tokenizer.hasMoreElements()) {
            String next = tokenizer.nextToken();
            if (StringUtils.isNotBlank(next)) {
                if (null != lastNonBlank) {
                    try { // test to see if its a realm
                        if (realmPath.toString().equalsIgnoreCase("/") && topLevel) {
                            ocm = new OrganizationConfigManager(getSSOToken(), realmPath.toString() + lastNonBlank);
                            realmPath.append(lastNonBlank);
                            topLevel = false;
                        } else {
                            ocm = new OrganizationConfigManager(getSSOToken(), realmPath.toString() + "/" + lastNonBlank);
                            realmPath.append("/").append(lastNonBlank);
                        }
                        ocm = new OrganizationConfigManager(getSSOToken(), realmPath.toString());
                    } catch (SMSException smse){
                        // cannot retrieve realm, must be endpoint
                        debug.warning(next + "is the endpoint because it is not a realm");
                        endpoint = new StringBuilder("/");
                        endpoint.append(lastNonBlank);
                        if(!checkValidEndpoint(endpoint.toString())){
                            debug.warning(endpoint.toString() + "is the endpoint because it is not a realm");
                            throw new NotFoundException("Endpoint " + endpoint.toString() + " is not a defined endpoint.");
                        }
                        // add the rest of tokens as resource name
                        lastNonBlankID = next;
                        while (tokenizer.hasMoreElements()) {
                            next = tokenizer.nextToken();
                            if (StringUtils.isNotBlank(next)) {
                                if (null != lastNonBlankID) {
                                    if (null == resourceID) {
                                        resourceID = new StringBuilder(lastNonBlankID);
                                    } else {
                                        resourceID.append("/").append(lastNonBlankID);
                                    }
                                }
                                lastNonBlankID = next;
                            }
                        }

                    }
                }
                lastNonBlank = next;
            }
        }

        details.put("realmPath",realmPath.toString());

        if(null != endpoint && !endpoint.toString().isEmpty()){
            details.put("resourceName", endpoint.toString());
        } else {
            endpoint = new StringBuilder("/");
            details.put("resourceName",endpoint.append(lastNonBlank).toString());
        }
        if (null != resourceID) {
            details.put("resourceId", resourceID.append("/").append(lastNonBlankID).toString());
        } else {
            details.put("resourceId", lastNonBlankID);
        }

        return details;
    }
}