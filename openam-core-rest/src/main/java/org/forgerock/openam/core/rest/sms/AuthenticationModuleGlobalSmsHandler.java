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
* Copyright 2016 ForgeRock AS.
*/
package org.forgerock.openam.core.rest.sms;

import static org.forgerock.json.JsonValue.*;
import static org.forgerock.openam.rest.RestConstants.*;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.locale.AMResourceBundleCache;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceSchemaManager;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.services.context.Context;

/**
 * Handler for the global-realm/authentication/modules endpoint, which must
 * support getAllTypes functionality.
 *
 * @since 14.0.0
 */
public class AuthenticationModuleGlobalSmsHandler extends DefaultSmsHandler {

    protected SSOToken adminToken;
    protected Debug debug;
    protected Set<String> authenticationServiceNames;
    protected AMResourceBundleCache resourceBundleCache;
    protected java.util.Locale defaultLocale;

    @Inject
    public AuthenticationModuleGlobalSmsHandler(@Named("frRest") Debug debug, @Named("adminToken") SSOToken adminToken,
            @Named("AMAuthenticationServices") Set<String> authenticationServiceNames,
            @Named("AMResourceBundleCache") AMResourceBundleCache resourceBundleCache,
            @Named("DefaultLocale") Locale defaultLocale) {
        this.debug = debug;
        this.adminToken = adminToken;
        this.authenticationServiceNames = authenticationServiceNames;
        this.resourceBundleCache = resourceBundleCache;
        this.defaultLocale = defaultLocale;
    }

    @Override
    public JsonValue getAllTypes(Context context, ActionRequest request)
            throws NotSupportedException, InternalServerErrorException {
        final List<Object> jsonArray = array();

        try {
            for (String serviceName : authenticationServiceNames) {
                ServiceSchemaManager schemaManager = new ServiceSchemaManager(serviceName, adminToken);

                String resourceId = schemaManager.getResourceName();
                String typeI18N = getI18NValue(schemaManager, resourceId, debug);
                jsonArray.add(object(
                        field(ResourceResponse.FIELD_CONTENT_ID, resourceId),
                        field(NAME, typeI18N)));
            }

            return json(object(field(RESULT, jsonArray)));
        } catch (SMSException | SSOException e) {
            debug.error("AuthenticationModuleGlobalCollectionHandler::getAllTypes - Unable to query SMS config", e);
            throw new InternalServerErrorException("Unable to query SMS config: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonValue getCreatableTypes(Context context, ActionRequest request)
            throws NotSupportedException, InternalServerErrorException {
        throw new NotSupportedException("Operation not supported");
    }

    @Override
    public JsonValue getSchema(Context context, ActionRequest request)
            throws NotSupportedException, InternalServerErrorException {
        throw new NotSupportedException("Operation not supported");
    }

    @Override
    public JsonValue getTemplate(Context context, ActionRequest request)
            throws NotSupportedException, InternalServerErrorException {
        throw new NotSupportedException("Operation not supported");
    }

    protected String getI18NValue(ServiceSchemaManager schemaManager, String authType, Debug debug) {
        String i18nKey = schemaManager.getI18NKey();
        String i18nName = authType;
        ResourceBundle rb = getBundle(schemaManager.getI18NFileName(), defaultLocale);
        if (rb != null && i18nKey != null && !i18nKey.isEmpty()) {
            i18nName = com.sun.identity.shared.locale.Locale.getString(rb, i18nKey, debug);
        }
        return i18nName;
    }

    protected ResourceBundle getBundle(String name, Locale locale) {
        return resourceBundleCache.getResBundle(name, locale);
    }

}