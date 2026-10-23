/*
 * Copyright 2026 RAKKAU
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rakkau.midpoint.servicedeskplus;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuration of the ManageEngine ServiceDesk Plus connector.
 */
public class ServiceDeskConfiguration extends AbstractConfiguration {

    private String baseUrl;
    private GuardedString authToken;
    private int pageSize = 100;
    private int timeoutSeconds = 30;
    private boolean trustAllCertificates = false;

    @ConfigurationProperty(displayMessageKey = "serviceDesk.baseUrl", helpMessageKey = "serviceDesk.baseUrl.help",
            required = true, order = 1)
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    @ConfigurationProperty(displayMessageKey = "serviceDesk.authToken", helpMessageKey = "serviceDesk.authToken.help",
            required = true, confidential = true, order = 2)
    public GuardedString getAuthToken() { return authToken; }
    public void setAuthToken(GuardedString authToken) { this.authToken = authToken; }

    @ConfigurationProperty(displayMessageKey = "serviceDesk.pageSize", helpMessageKey = "serviceDesk.pageSize.help",
            order = 3)
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    @ConfigurationProperty(displayMessageKey = "serviceDesk.timeoutSeconds",
            helpMessageKey = "serviceDesk.timeoutSeconds.help", order = 4)
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @ConfigurationProperty(displayMessageKey = "serviceDesk.trustAllCertificates",
            helpMessageKey = "serviceDesk.trustAllCertificates.help", order = 5)
    public boolean isTrustAllCertificates() { return trustAllCertificates; }
    public void setTrustAllCertificates(boolean trustAllCertificates) { this.trustAllCertificates = trustAllCertificates; }

    @Override
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("The 'baseUrl' parameter is required.");
        }
        String normalized = baseUrl.trim();
        if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) {
            throw new IllegalArgumentException("The 'baseUrl' parameter must start with http:// or https://.");
        }
        if (isBlank(authToken)) {
            throw new IllegalArgumentException("The 'authToken' parameter is required.");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("The 'pageSize' parameter must be between 1 and 100.");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("The 'timeoutSeconds' parameter must be between 1 and 300.");
        }
    }

    private static boolean isBlank(GuardedString secret) {
        if (secret == null) {
            return true;
        }
        final boolean[] blank = {true};
        secret.access(chars -> {
            for (char c : chars) {
                if (!Character.isWhitespace(c)) {
                    blank[0] = false;
                    return;
                }
            }
        });
        return blank[0];
    }
}
