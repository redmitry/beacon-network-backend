/**
 * *****************************************************************************
 * Copyright (C) 2024 ELIXIR ES, Spanish National Bioinformatics Institute (INB)
 * and Barcelona Supercomputing Center (BSC)
 *
 * Modifications to the initial code base are copyright of their respective
 * authors, or their employers as appropriate.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 * *****************************************************************************
 */

package es.bsc.inb.ga4gh.beacon.network.engine;

import es.bsc.inb.ga4gh.beacon.network.config.NetworkConfiguration;
import es.bsc.inb.ga4gh.beacon.network.model.AccessTokenResponse;
import es.bsc.inb.ga4gh.beacon.network.model.OauthProtectedResource;
import es.bsc.inb.ga4gh.beacon.network.model.OidcConfigurationProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.bind.Jsonb;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Dmitry Repchevsky
 */

@ApplicationScoped
public class BeaconNetworkTokenExchanger {
    
    @Inject
    private NetworkConfiguration network_configuration;
    
    @Inject
    private Jsonb jsonb;
    
    private HttpClient http_client;
    private Map<String, OidcConfigurationProvider> configuration_providers;

    @PostConstruct
    public void init() {
        http_client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(30))
        .build();
        
        configuration_providers = new ConcurrentHashMap();
    }
    
    public List<String> exchange(String endpoint, List<String> headers) {
        return headers.stream().map(h -> exchangeHeader(endpoint, h)).toList();
    }
    
    private String exchangeHeader(String endpoint, String header) {
        if (header != null && header.startsWith("Bearer ")) {
            final String token = exchangeToken(endpoint, header.substring(7));
            if (token != null) {
                return "Bearer " + token;
            }
        }
        return header;
    }
    
    private String exchangeToken(String endpoint, String token) {
        final OauthProtectedResource resource = network_configuration.getProtectedResources().get(endpoint);
        if (resource != null) {
            final String client_id = resource.getClientId();
            final List<String> authorization_servers = resource.getAuthorizationServers();
            if (client_id != null && authorization_servers != null) {
                final String[] token_parts = token.split("\\.");
                if (token_parts.length == 3) {
                    final JsonObject payload = decode(token_parts[1]);
                    if (payload != null) {
                        final String issuer = payload.getString("iss", null);
                        if (!authorization_servers.contains(issuer)) {
                            // need exchange
                            final List<OidcConfigurationProvider> providers = getWellKnownProviders(authorization_servers);
                            if (providers != null) {
                                for (OidcConfigurationProvider provider : providers) {
                                    final Builder builder = createTokenExchangeRequest(provider, client_id, token);
                                    try {
                                        final HttpResponse<String> response = http_client.send(builder.build(), 
                                                BodyHandlers.ofString(StandardCharsets.UTF_8));
                                        if (response != null) {
                                            final String body = response.body();
                                            if (body != null) {
                                                final AccessTokenResponse atResponse = 
                                                        jsonb.fromJson(body, AccessTokenResponse.class);
                                                
                                                final String accessToken = atResponse.getAccessToken();
                                                if (accessToken != null) {
                                                    return accessToken;
                                                }
                                            }                                            
                                        }
                                    } catch (Exception ex) {
                                        Logger.getLogger(BeaconNetworkTokenExchanger.class.getName()).log(
                                                Level.INFO, ex.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private List<OidcConfigurationProvider> getWellKnownProviders(List<String> authorization_servers) {
        
        final List<OidcConfigurationProvider> providers = new ArrayList();
        
        final List<CompletableFuture<HttpResponse<String>>> invocations = new ArrayList();
        for (String authorization_server : authorization_servers) {
            final OidcConfigurationProvider provider = configuration_providers.get(authorization_server);
            if (provider != null) {
                providers.add(provider);
            } else {
                final Builder builder = createWellKnownProviderRequest(authorization_server);
                final CompletableFuture<HttpResponse<String>> future =
                    http_client.sendAsync(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
                invocations.add(future);
            }
        }
        for (CompletableFuture<HttpResponse<String>> invocation : invocations) {
            try {
                final HttpResponse<String> response = invocation.get(30, TimeUnit.SECONDS);
                if (response != null && response.statusCode() < 300) {
                    final String body = response.body();
                    if (body != null) {
                        final OidcConfigurationProvider provider = 
                                jsonb.fromJson(body, OidcConfigurationProvider.class);
                        providers.add(provider);
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(BeaconNetworkTokenExchanger.class.getName()).log(
                        Level.INFO, ex.getMessage());
            }
        }
        return providers;
    }
    
    private Builder createWellKnownProviderRequest(String authorization_server) {
        return HttpRequest.newBuilder(UriBuilder.fromUri(authorization_server)
                .path(".well-known/openid-configuration").build())
                .header(HttpHeaders.USER_AGENT, "BN/2.0.0")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);
    }

    private Builder createTokenExchangeRequest(OidcConfigurationProvider provider,
            String client_id, String token) {
        
        final StringBuilder data = new StringBuilder();

        data.append("client_id").append('=').append(client_id)
            .append("&subject_token").append('=').append(token)
            .append("&grant_type").append('=')
                .append(URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange", StandardCharsets.UTF_8))
            .append("&subject_token_type").append('=')
                .append(URLEncoder.encode("urn:ietf:params:oauth:token-type:jwt", StandardCharsets.UTF_8))
            .append("&requested_token_type").append('=')
                .append(URLEncoder.encode("urn:ietf:params:oauth:token-type:access_token", StandardCharsets.UTF_8));
        
        return HttpRequest.newBuilder(UriBuilder.fromUri(provider.getTokenEndpoint())
                .build())
                .header(HttpHeaders.USER_AGENT, "BN/2.0.0")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .POST(BodyPublishers.ofString(data.toString(), StandardCharsets.UTF_8));
    }
    
    private JsonObject decode(String base64) {
        final Base64.Decoder decoder = Base64.getDecoder();
        try {
            final byte[] b = decoder.decode(base64);
            return Json.createReader(new ByteArrayInputStream(b)).readObject();
        } catch (Exception ex) {
            return null;
        }
    }
}
