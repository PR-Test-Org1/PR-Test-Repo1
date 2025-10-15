/*
 * Copyright (c) 2024 Black Duck, Inc. All rights reserved worldwide.
 */
package com.blackduck.altair.report.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.blackduck.altair.api.model.AltairLink;
import com.blackduck.altair.report.common.client.rest.IlmSpecializationClient;
import com.blackduck.altair.report.common.client.rest.PortfolioServiceRestClient;
import com.blackduck.altair.report.common.dto.ilmspecialization.ComponentOrigin;
import com.blackduck.altair.report.common.dto.ilmspecialization.ComponentOriginDto;
import com.blackduck.altair.report.common.dto.ilmspecialization.ComponentVersion;
import com.blackduck.altair.report.common.dto.ilmspecialization.ComponentVersionDto;
import com.blackduck.altair.report.common.dto.ilmspecialization.License;
import com.blackduck.altair.report.common.dto.ilmspecialization.LicenseDetail;
import com.blackduck.altair.report.common.dto.ilmspecialization.LicenseDetailsDto;
import com.blackduck.altair.report.common.dto.portfolio.PortfolioItem;
import com.blackduck.altair.report.common.dto.report.Link;
import com.blackduck.altair.report.common.util.ErrorMessageReader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.blackduck.altair.report.common.constants.Constants.ILMSpecializationRestAPI.COMPONENT_VERSION;
import static com.blackduck.altair.report.common.constants.Constants.ILMSpecializationRestAPI.COMPONENT_VERSIONS;
import static com.blackduck.altair.report.common.constants.Constants.ILMSpecializationRestAPI.LICENSE;
import static com.blackduck.altair.report.common.constants.Constants.ILMSpecializationRestAPI.LICENSES;

@RequiredArgsConstructor
@Slf4j
public class SbomReportService {
    protected final IlmSpecializationClient ilmSpecializationClient;
    protected final PortfolioServiceRestClient portfolioServiceRestClient;
    protected final ErrorMessageReader errorMessageReader;

    protected PortfolioItem getApplicationById(String id, String orgId, String token) {
        return portfolioServiceRestClient.getApplicationById(id, orgId, token);
    }


    /**
     * Fetch the license details
     *
     * @param portfolioSubItemId - project unique uuid
     * @param orgId              - tenant unique uuid
     * @param token              - access token
     * @return - License details
     */
    protected Map<String, LicenseDetail> getLicenseDetails(String portfolioSubItemId, String orgId, String token) {
        LicenseDetailsDto licenseDetailsDto = ilmSpecializationClient.getLicenseDetails(portfolioSubItemId, orgId, token);
        if (licenseDetailsDto == null || CollectionUtils.isEmpty(licenseDetailsDto.getItems())) {
            return new HashMap<>();
        }
        List<LicenseDetail> licenseDetails = new ArrayList<>(licenseDetailsDto.getItems());
        if (licenseDetailsDto.getItems().size() == licenseDetailsDto.getCollection().getItemCount()) {
            log.info("Completed fetching all license details for the  org {} and project {}", orgId, portfolioSubItemId);
            return processLicenseDetails(licenseDetails);
        }
        int fetched = licenseDetailsDto.getItems().size();
        int totalDetails = licenseDetailsDto.getCollection().getItemCount();
        LicenseDetailsDto tempDetails = licenseDetailsDto;
        try {
            while (fetched < totalDetails) {
                AltairLink nextLink = getNextLink(tempDetails.getLinks());
                if (nextLink == null) {
                    log.info("Completed fetching all data.");
                    break;
                }
                UriComponents uriComponents = UriComponentsBuilder.fromUriString(URLDecoder.decode(nextLink.getHref(), StandardCharsets.UTF_8)).build();
                tempDetails = ilmSpecializationClient.getLicenseDetails(portfolioSubItemId, orgId, token, uriComponents.getQuery());
                licenseDetails.addAll(tempDetails.getItems());
                fetched += tempDetails.getItems().size();
                log.info("Fetched {}/{} license details", fetched, totalDetails);
            }
        } catch (Exception e) {
            throw new RuntimeException(errorMessageReader.getProperty("report.generation.sbom.license.details.failed", e.getMessage()));
        }
        return processLicenseDetails(licenseDetails);
    }

    /**
     * fetches all component versions using pagination
     *
     * @param portfolioSubItemId - project unique uuid
     * @param orgId              - tenant unique uuid
     * @param token              -  access token
     * @return - list of component versions
     */
    @VisibleForTesting
    protected List<ComponentVersion> getComponentVersions(String portfolioSubItemId, String orgId, String token, List<String> tools) {
        ComponentVersionDto componentVersionDto = ilmSpecializationClient.getComponentVersionList(portfolioSubItemId, orgId, token, tools);
        if (CollectionUtils.isEmpty(componentVersionDto.getItems())) {
            return new ArrayList<>();
        }
        List<ComponentVersion> componentVersions = new ArrayList<>(componentVersionDto.getItems());
        if (componentVersionDto.getItems().size() == componentVersionDto.getCollection().getItemCount()) {
            log.info("Completed fetching all component versions for the org {} and project {}", orgId, portfolioSubItemId);
            processComponentVersions(componentVersions);
            return componentVersions;
        }
        int fetched = componentVersionDto.getItems().size();
        int totalVersions = componentVersionDto.getCollection().getItemCount();
        ComponentVersionDto tempVersions = componentVersionDto;
        try {
            while (fetched < totalVersions) {
                AltairLink nextLink = getNextLink(tempVersions.getLinks());
                if (nextLink == null) {
                    log.info("Completed fetching all data.");
                    break;
                }
                UriComponents uriComponents = UriComponentsBuilder.fromUriString(URLDecoder.decode(nextLink.getHref(), StandardCharsets.UTF_8))
                    .build();
                tempVersions = ilmSpecializationClient.getComponentVersionList(portfolioSubItemId, orgId, token, uriComponents.getQuery());
                componentVersions.addAll(tempVersions.getItems());
                fetched += tempVersions.getItems().size();
                log.info("Fetched {}/{} component versions", fetched, totalVersions);
            }
        } catch (Exception e) {
            throw new RuntimeException(errorMessageReader.getProperty("report.generation.sbom.component.versions.failed", e.getMessage()));
        }
        processComponentVersions(componentVersions);
        return componentVersions;
    }

    private void processComponentVersions(List<ComponentVersion> componentVersions) {
        componentVersions.forEach(componentVersion -> {
            // Check and process the licenses field
            if (componentVersion.getLicenseDefinition().getLicenses() != null) {
                componentVersion.getLicenseDefinition().getLicenses().forEach(license -> {
                    license.setId(extractLicenseId(license));
                });
            }

            // Check and process the license field
            if (componentVersion.getLicenseDefinition().getLicense() != null) {
                License license = componentVersion.getLicenseDefinition().getLicense();
                license.setId(extractLicenseId(license));
            }

            // Check and process the original licenses field
            if (componentVersion.getOriginalLicenseDefinition().getLicenses() != null) {
                componentVersion.getOriginalLicenseDefinition().getLicenses().forEach(license -> {
                    license.setId(extractLicenseId(license));
                });
            }

            // Check and process the original license field
            if (componentVersion.getOriginalLicenseDefinition().getLicense() != null) {
                License license = componentVersion.getOriginalLicenseDefinition().getLicense();
                license.setId(extractLicenseId(license));
            }
        });
    }

    private static @Nullable String extractLicenseId(License licenseDefinition) {
        return licenseDefinition.getLinks().stream()
            .filter(link -> LICENSE.equals(link.getRelation()))
            .map(AltairLink::getHref)
            .map(href -> extractIdFromLink(href, LICENSES))
            .findFirst()
            .orElse(null);
    }

    /**
     * Fetches all component origins using pagination
     *
     * @param portfolioSubItemId - project unique uuid
     * @param orgId              - tenant unique uuid
     * @param token              - access token
     * @return - processed component origin data
     */
    protected Map<String, Set<ComponentOrigin>> getComponentOrigins(String portfolioSubItemId, String orgId, String token) {
        ComponentOriginDto componentOriginDto = ilmSpecializationClient.getComponentOriginList(portfolioSubItemId, orgId, token);
        if (componentOriginDto == null || CollectionUtils.isEmpty(componentOriginDto.getItems())) {
            return new HashMap<>();
        }
        if (componentOriginDto.getItems().size() == componentOriginDto.getCollection().getItemCount()) {
            log.info("Completed fetching all component version origins for the org {} and project {}", orgId, portfolioSubItemId);
            return processComponentOrigins(componentOriginDto.getItems());
        }
        List<ComponentOrigin> componentOrigins = new ArrayList<>(componentOriginDto.getItems());
        int fetched = componentOriginDto.getItems().size();
        int totalOrigins = componentOriginDto.getCollection().getItemCount();
        ComponentOriginDto tempOrigins = componentOriginDto;
        try {
            while (fetched < totalOrigins) {
                AltairLink nextLink = getNextLink(tempOrigins.getLinks());
                if (nextLink == null) {
                    log.info("Completed fetching all data.");
                    break;
                }
                UriComponents uriComponents = UriComponentsBuilder.fromUriString(URLDecoder.decode(nextLink.getHref(), StandardCharsets.UTF_8))
                    .build();
                tempOrigins = ilmSpecializationClient
                    .getComponentOriginList(portfolioSubItemId, orgId, token, uriComponents.getQuery());
                componentOrigins.addAll(tempOrigins.getItems());
                fetched += tempOrigins.getItems().size();
                log.info("Fetched {}/{} component version origins", fetched, totalOrigins);
            }
        } catch (Exception e) {
            throw new RuntimeException(errorMessageReader.getProperty("report.generation.sbom.component.versions.origin.failed", e.getMessage()));
        }
        return processComponentOrigins(componentOrigins);
    }

    /**
     * Process the component origins to form map of component version and set of component origins
     *
     * @param componentOrigins - list of component origins to process before generating the report
     * @return - processed component origin data
     */

    private Map<String, Set<ComponentOrigin>> processComponentOrigins(List<ComponentOrigin> componentOrigins) {
        Map<String, Set<ComponentOrigin>> componentVersionToComponentOrigins = new HashMap<>();
        componentOrigins
            .forEach(componentOrigin -> {
                // fetch component version id from the link
                String componentVersionId = componentOrigin.getLinks().stream()
                    .filter(link -> COMPONENT_VERSION.equals(link.getRel()))
                    .map(Link::getHref)
                    .map(href -> extractIdFromLink(href, COMPONENT_VERSIONS))
                    .findFirst()
                    .orElse(null);

                Set<ComponentOrigin> origins = componentVersionToComponentOrigins.getOrDefault(componentVersionId, new HashSet<>());
                componentOrigin.setComponentVersionId(componentVersionId);
                componentVersionToComponentOrigins.put(componentVersionId, origins);
                origins.add(componentOrigin);
            });
        return componentVersionToComponentOrigins;
    }

    /**
     * Extracts the id from the URL
     *
     * @param url      - url link
     * @param resource - resource type
     * @return - returns resource id
     */
    private static String extractIdFromLink(String url, String resource) {
        String path = url.split("\\?")[0];
        String[] urlParts = path.split("/");
        int index = java.util.Arrays.asList(urlParts).indexOf(resource);
        return (index != -1 && index + 1 < urlParts.length) ? urlParts[index + 1] : null;
    }

    private Map<String, LicenseDetail> processLicenseDetails(@NotNull List<LicenseDetail> licenseDetails) {
        return licenseDetails.stream().collect(Collectors.toMap(LicenseDetail::getId, v -> v));
    }

    /**
     * Find and return the next link for the pagination
     *
     * @param links - fetch the next link for pagination if data is more than max limit
     * @return - AltairLink for next page
     */
    protected AltairLink getNextLink(List<AltairLink> links) {
        if (links == null) {
            throw new RuntimeException("Links were null.");
        }
        return links.stream().filter(link -> link.getRelation().equalsIgnoreCase("next"))
            .findAny().orElse(null);
    }
}
