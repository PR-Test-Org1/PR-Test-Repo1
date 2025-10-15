/*
 * Copyright (c) 2025 Black Duck, Inc. All rights reserved worldwide.
 */
package com.blackduck.altair.report.service;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.blackduck.altair.report.client.PortfolioRpcClient;
import com.blackduck.altair.report.common.client.rest.GCSClient;
import com.blackduck.altair.report.common.client.rpc.StorageManagerClient;
import com.blackduck.altair.report.common.config.DefaultConfiguration;
import com.blackduck.altair.report.common.config.featureflag.FeatureFlagService;
import com.blackduck.altair.report.common.constants.Constants;
import com.blackduck.altair.api.AltairResponseEntity;
import com.blackduck.altair.api.AltairSingleResponse;
import com.blackduck.altair.api.model.AltairLink;
import com.blackduck.altair.report.common.config.TenantAwareContext;
import com.blackduck.altair.report.common.dto.portfolio.Branch;
import com.blackduck.altair.report.common.dto.portfolio.PortfolioSubItem;
import com.blackduck.altair.report.common.dto.report.ApplicationDtoV3;
import com.blackduck.altair.report.common.dto.report.GenerateReportRequestDto;
import com.blackduck.altair.report.common.dto.report.GenerateReportRequestDtoV2;
import com.blackduck.altair.report.common.dto.report.GenerateReportRequestDtoV3;
import com.blackduck.altair.report.common.dto.report.GroupReportTypeDTO;
import com.blackduck.altair.report.common.dto.report.ProjectDto;
import com.blackduck.altair.report.common.dto.report.ReportDTO;
import com.blackduck.altair.report.common.dto.report.ReportTypeDTO;
import com.blackduck.altair.report.common.dto.report.ReportTypeData;
import com.blackduck.altair.report.common.exception.InvalidReportTypeException;
import com.blackduck.altair.report.common.enums.CIAMScope;
import com.blackduck.altair.report.common.enums.ReportScope;
import com.blackduck.altair.report.common.enums.ReportStatus;
import com.blackduck.altair.report.common.enums.ReportTemplate;
import com.blackduck.altair.report.common.enums.ReportType;
import com.blackduck.altair.report.common.enums.Severity;
import com.blackduck.altair.report.common.enums.ToolId;
import com.blackduck.altair.report.common.exception.DataNotFoundException;
import com.blackduck.altair.report.common.exception.ForbiddenException;
import com.blackduck.altair.report.common.exception.MethodNotAllowedException;
import com.blackduck.altair.report.common.exception.ReportGeneratorException;
import com.blackduck.altair.report.common.exception.ReportLimitException;
import com.blackduck.altair.report.common.exception.TenantDataDeletionException;
import com.blackduck.altair.report.common.exception.ValidationException;
import com.blackduck.altair.report.common.model.entity.Configuration;
import com.blackduck.altair.report.common.model.entity.Reports;
import com.blackduck.altair.report.common.model.entity.ReportsProject;
import com.blackduck.altair.report.common.model.entity.ReportsProjectID;
import com.blackduck.altair.report.common.model.report.UserInfo;
import com.blackduck.altair.report.common.model.storage.ArtifactModel;
import com.blackduck.altair.report.common.service.CiamService;
import com.blackduck.altair.report.common.util.CommonRSQLValidator;
import com.blackduck.altair.report.common.util.CommonUtils;
import com.blackduck.altair.report.common.util.ConfigurationUtil;
import com.blackduck.altair.report.common.util.ErrorMessageReader;
import com.blackduck.altair.report.common.util.PaginationUtil;
import com.blackduck.altair.report.common.util.ServiceTokenUtil;
import com.blackduck.altair.report.dataholder.DataHolder;
import com.blackduck.altair.report.repository.ConfigurationRepository;
import com.blackduck.altair.report.repository.ReportsProjectRepository;
import com.blackduck.altair.report.repository.ReportsRepository;
import com.blackduck.altair.report.service.bigQuery.BigQueryService;
import com.blackduck.altair.report.service.report.DASTDetailReportGenerator;
import com.blackduck.altair.report.service.report.DeveloperDetailSCAReportGenerator;
import com.blackduck.altair.report.service.report.DeveloperDetailStaticReportGenerator;
import com.blackduck.altair.report.service.report.ExecutiveSummaryReportGenerator;
import com.blackduck.altair.report.service.report.IssueOverviewReportGenerator;
import com.blackduck.altair.report.service.report.IssuesReportGenerator;
import com.blackduck.altair.report.service.report.ReportGenerator;
import com.blackduck.altair.report.service.report.SPDXReportGenerator;
import com.blackduck.altair.report.service.report.SecurityAuditReportGenerator;
import com.blackduck.altair.report.service.report.StandardComplianceDetailReportGenerator;
import com.blackduck.altair.report.service.report.StandardComplianceReportGenerator;
import com.blackduck.altair.report.service.report.TestSummaryReportGenerator;
import com.blackduck.altair.report.util.StorageServiceUtil;
import com.blackduck.altair.report.util.Validator;
import com.blackduck.polaris.storage.service.StorageProtos;
import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.el.MethodNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.blackduck.altair.report.common.config.DefaultConfiguration.REPORT_FORMAT;
import static com.blackduck.altair.report.common.config.DefaultConfiguration.SPDX_FORMAT;
import static com.blackduck.altair.report.common.constants.Constants.PARAM.VALID_REPORTS_FILTERS;
import static com.blackduck.altair.report.common.constants.Constants.PARAM.VALID_SORT_BY_REPORTS_PARAMETERS;
import static com.blackduck.altair.report.common.util.CommonUtils.buildFilterForQuery;
import static com.blackduck.altair.report.common.util.CommonUtils.consoleApiTime;
import static com.blackduck.altair.report.service.report.ReportGenerator.objectMapper;

import static io.github.perplexhub.rsql.RSQLJPASupport.toSpecification;

/**
 * Service Class for ReportGeneratorController
 *
 * @author agarwalj
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportService {

    private static final Set<String> UNSUPPORTED_V1_REPORT_TYPES = Set.of(
        ReportType.EXECUTIVE_SUMMARY_REPORT.name.toLowerCase(),
        ReportType.STANDARD_COMPLIANCE_REPORT.name.toLowerCase(),
        ReportType.SECURITY_AUDIT_REPORT.name.toLowerCase(),
        ReportType.TEST_SUMMARY_REPORT.name.toLowerCase(),
        ReportType.ISSUE_OVERVIEW_REPORT.name.toLowerCase(),
        ReportType.DEVELOPER_DETAIL_STATIC_REPORT.name.toLowerCase(),
        ReportType.DEVELOPER_DETAIL_SCA_REPORT.name.toLowerCase(),
        ReportType.STANDARD_COMPLIANCE_DETAIL_REPORT.name.toLowerCase(),
        ReportType.DAST_DETAIL_REPORT.name.toLowerCase()
    );

    private final StorageManagerClient storageManagerClient;
    private final ReportsRepository reportsRepository;
    private final ReportsProjectRepository reportsProjectRepository;
    private final GCSClient gcsClient;
    private final BigQueryService bigQueryService;
    private final ConfigurationService configurationService;
    private final NotificationAndAuditService notificationAndAuditService;
    private final ErrorMessageReader errorMessageReader;
    private final FeatureFlagService featureFlagService;
    private final SPDXReportService spdxReportService;
    private final CycloneDXReportService cycloneDXReportService;
    private final CiamService ciamService;
    private final PortfolioService portfolioService;
    private final PortfolioRpcClient scopeConfiguration;
    private final ServiceTokenUtil serviceTokenUtil;
    private final ConfigurationRepository configurationRepository;

    @Value("${report-generation.path}")
    private String reportsBasePath;
    @Value("${root.domain.url}")
    private String rootDomainUrl;
    @Value("${report-generation.allowed-max-report-per-user}")
    private int allowedMaxReportPerUser;

    @Async
    public void beginReportGenerationWorkflow(Reports reportsEntry, GenerateReportRequestDtoV3 requestDto, ReportType reportType, String reportFormat,
                                              String tenantId, UserInfo userInfo, String apiTokenName, String clientIp, String hostName, String contentType, Configuration configuration) {
        // implement create Dataset And Generate Report here.
        log.info("Report ID: {} - Request body {} passed all validations and ready to create dataset for generating report.", reportsEntry != null ? reportsEntry.getId() : "null", requestDto);
        long startTime = System.currentTimeMillis();
        if (reportsEntry == null) {
            reportsEntry = initiateReportEntry(requestDto, reportType, tenantId, userInfo);
        }
        File reportFile = null;
        Reports reportsEntryOld;
        String token = null;
        ReportTemplate reportTemplate = null;
        try {
            // Content Type is V1 and Report Type is not SBOM
            // Then extract the app name, project name and branch info and set to requestDto
            // set scope to SELECTED_APPLICATIONS
            // set defaultBranchOnly to true
            // prepare filter and set to requestDto

            if (!reportType.isBOM && (contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.REPORT_GENERATE_POLARIS_INSIGHTS_MEDIA_TYPE_V1) ||
                contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.CONFIGURATION_MEDIA_TYPE))) {
                configurationService.updateRequestDtoWithPortfolioDetails(requestDto, reportsEntry, tenantId);
            }
            // fetch applications and projects based on filter
            if (featureFlagService.isBranchSupportEnabled() && !reportType.isBOM && (contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.REPORT_POLARIS_INSIGHTS_MEDIA_TYPE_V2) || contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.CONFIGURATION_MEDIA_TYPE_V2))) {
                //fetch applications and projects based on scope
                scopeConfiguration.fetchApplicationAndProjectsBasedOnScope(requestDto, userInfo, tenantId);

                String scope = requestDto.getScope();
                ReportScope reportScope = ReportScope.valueOf(scope.toUpperCase());
                if (reportScope != ReportScope.ALL_APPLICATIONS) {
                    //fetch label names based on filter
                    Set<String> labelNames = configurationService.fetchLabelNames(requestDto.getFilter(), tenantId);
                    // Validate filter elements if scope is not ALL_APPLICATIONS
                    Validator.validateFilterElements(requestDto.getFilter(), requestDto.getApplications(), labelNames, configuration != null ? configuration.getId() : null);
                }


                if (configuration != null) {
                    configuration.setConfiguration(CommonUtils.getJsonString(requestDto)); //Set new configuration with applicationData
                    ConfigurationUtil.updateProjectCount(configuration, requestDto.getApplications(), userInfo.getId());
                    configurationRepository.save(configuration);
                }
                reportsEntry = updateReportConfigurations(reportsEntry, requestDto);
            }
            log.info("Report ID: {} - Fetching applications and projects based on filter: {}", reportsEntry.getId(), requestDto.getApplications().size());
            token = serviceTokenUtil.getToken(tenantId);
            reportTemplate = getReportTemplate(reportType, reportFormat);
            // to publish report generation initiation notification
            notificationAndAuditService.publishInitiationNotification(tenantId, apiTokenName, clientIp,
                userInfo, reportTemplate.reportType, token, reportsEntry, hostName);
            setDefaultValues(requestDto, reportType);
            updateReports(reportsEntry, ReportStatus.IN_PROGRESS, null, null);
            ReportGenerator reportGenerator = ReportGenerator.fromReportType(reportTemplate, reportsBasePath, featureFlagService, tenantId);
            DataHolder dataHolder = getDataholder(reportGenerator, tenantId, requestDto, token, hostName);
            reportFile = reportGenerator.generate(requestDto, userInfo, dataHolder, reportsEntry);
            token = serviceTokenUtil.getToken(tenantId);
            String artifactId = createAndUploadArtifact(reportFile, tenantId, token);
            reportsEntryOld = copyEntity(reportsEntry);
            reportsEntry.setResourceID(artifactId);
            reportsEntry.setMessage("Success");
            reportsEntry = updateReports(reportsEntry, ReportStatus.COMPLETED, null, reportFile);
            updateConfiguration(reportsEntry, UUID.fromString(tenantId), userInfo.getId());
            // to publish report generation success notification
            notificationAndAuditService.publishSuccessNotification(tenantId, apiTokenName, clientIp, userInfo, reportTemplate.reportType, token, reportsEntryOld, reportsEntry, hostName);
            log.debug("Report ID: {} - Report uploaded to GCS successfully.", reportsEntry.getId());
            consoleApiTime(startTime, "to complete the report generation workflow");
        } catch (Exception e) {
            String errorStatus;
            String errorMessage;
            if (e instanceof DataNotFoundException) {
                log.warn("Report ID: {} - Report generation failed due to {}", reportsEntry.getId(), e.getMessage());
                errorStatus = Constants.ExceptionMessages.REPORT_SCOPE_ERROR_MESSAGE;
                errorMessage = String.format(Constants.ExceptionMessages.REPORT_GENERATION_AUDIT_STATUS_TEMPLATE,
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.BAD_REQUEST.name(),
                    "Reason: Data not found.");
            } else if (e instanceof ForbiddenException) {
                log.warn("Report ID: {} - Report generation failed due to {}", reportsEntry.getId(), e.getMessage());
                errorStatus = Constants.ExceptionMessages.REPORT_FORBIDDEN_REQUEST_EXCEPTION;
                errorMessage = String.format(Constants.ExceptionMessages.REPORT_GENERATION_AUDIT_STATUS_TEMPLATE,
                    HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.name(),
                    "Reason: Access denied for this operation.");
            } else {
                log.error("Report ID: {} - Failed to generate report. Cause: {}", reportsEntry.getId(), e.getMessage());
                errorStatus = Constants.ExceptionMessages.REPORT_GENERATION_FAILURE_MESSAGE;
                errorMessage = String.format(Constants.ExceptionMessages.REPORT_GENERATION_AUDIT_STATUS_TEMPLATE,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    HttpStatus.INTERNAL_SERVER_ERROR.name(),
                    "Reason: We are not able to process your request right now. Please try again later.");
            }
            reportsEntryOld = copyEntity(reportsEntry);
            reportsEntry.setStatus(ReportStatus.FAILED.name());
            reportsEntry.setFailureReason(e.getMessage());
            reportsEntry.setMessage(errorStatus);

            reportsEntry = updateReports(reportsEntry, null);
            // to publish report generation failure notification
            notificationAndAuditService.publishFailureNotification(tenantId, apiTokenName, clientIp, userInfo,
                reportTemplate == null ? reportType : reportTemplate.reportType, token, reportsEntryOld, reportsEntry,
                errorMessage, requestDto, hostName);

            throw e;
        } finally {
            if (reportFile != null) {
                CommonUtils.deleteFile(reportFile.getAbsolutePath());
            }
        }
    }

    private Reports updateReportConfigurations(Reports reports, GenerateReportRequestDtoV3 requestDto) {
        reports.setConfiguration(CommonUtils.getJsonString(requestDto));
        return reportsRepository.save(reports);
    }

    private void updateConfiguration(Reports reportsEntry, UUID tenantId, String requestedUserId) {
        if (reportsEntry.getConfigurationId() != null) {
            Configuration configuration = configurationService.getConfigurationEntity(reportsEntry.getConfigurationId(), tenantId, requestedUserId);
            if (configuration != null) {
                configurationService.incrementRunCount(configuration);
            }
        }
    }

    private ReportTemplate getReportTemplate(ReportType reportType, String reportFormat) {
        if (reportType.equals(ReportType.ISSUES_REPORT)) {
            reportType = ReportType.ISSUE_SUMMARY_REPORT;
        }
        return ReportTemplate.getReportTemplateFromType(reportType, reportFormat);
    }

    private DataHolder getDataholder(ReportGenerator reportGenerator, String tenantId, GenerateReportRequestDtoV3 requestDto, String token, String rootDomainUrl) throws DataNotFoundException {
        if (reportGenerator instanceof IssuesReportGenerator) {
            return bigQueryService.fetchDataForIssuesReport(tenantId, requestDto);
        } else if (reportGenerator instanceof ExecutiveSummaryReportGenerator) {
            return bigQueryService.fetchDataForExecutiveSummaryReport(tenantId, requestDto);
        } else if (reportGenerator instanceof StandardComplianceReportGenerator) {
            return bigQueryService.fetchDataForStandardComplianceReport(tenantId, requestDto);
        } else if (reportGenerator instanceof SecurityAuditReportGenerator) {
            return bigQueryService.fetchDataForSecurityAuditReport(tenantId, requestDto);
        } else if (reportGenerator instanceof TestSummaryReportGenerator) {
            return bigQueryService.fetchDataForTestSummaryReport(tenantId, requestDto);
        } else if (reportGenerator instanceof IssueOverviewReportGenerator) {
            return bigQueryService.fetchDataForIssueOverviewReport(tenantId, requestDto);
        } else if (reportGenerator instanceof DeveloperDetailStaticReportGenerator) {
            return bigQueryService.fetchDataForDeveloperDetailStaticReport(tenantId, requestDto);
        } else if (reportGenerator instanceof DeveloperDetailSCAReportGenerator) {
            return bigQueryService.fetchDataForDeveloperDetailSCAReport(tenantId, requestDto);
        } else if (reportGenerator instanceof DASTDetailReportGenerator) {
            return bigQueryService.fetchDataForDeveloperDetailDASTReport(tenantId, requestDto);
        } else if (reportGenerator instanceof StandardComplianceDetailReportGenerator) {
            return bigQueryService.fetchDataForStandardComplianceDetailReport(tenantId, requestDto);
        } else if (reportGenerator instanceof SPDXReportGenerator) {
            return spdxReportService.fetchDataForSPDXReport(requestDto, tenantId, token, rootDomainUrl);
        }
        return cycloneDXReportService.fetchDataForCycloneDXReport(requestDto, tenantId, token);
    }

    private Reports copyEntity(Reports reportsEntry) {
        return Reports.builder()
            .id(reportsEntry.getId())
            .name(reportsEntry.getName())
            .reportType(reportsEntry.getReportType())
            .application(reportsEntry.getApplication())
            .startDate(reportsEntry.getStartDate())
            .completedDate(reportsEntry.getCompletedDate())
            .createdBy(reportsEntry.getCreatedBy())
            .configuration(reportsEntry.getConfiguration())
            .resourceID(reportsEntry.getResourceID())
            .organizationID(reportsEntry.getOrganizationID())
            .format(reportsEntry.getFormat())
            .status(reportsEntry.getStatus())
            .build();
    }

    private void setDefaultValues(GenerateReportRequestDtoV3 requestDto, ReportType reportType) {

        setTools(requestDto, reportType);
        setSeverities(requestDto);
    }

    private void setTools(GenerateReportRequestDtoV3 requestDto, ReportType reportType) {
        List<String> tools;
        if (!CollectionUtils.isEmpty(requestDto.getTools())) {
            tools = requestDto.getTools().stream()
                .map(toolValue -> ToolId.getToolIdByValue(toolValue).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else {
            tools = ToolId.getAllAsList();
        }
        if (!ReportType.TEST_SUMMARY_REPORT.name.equalsIgnoreCase(reportType.name)) {
            if (tools.contains(ToolId.STATIC_POLARIS.getToolId())) {
                tools.add(ToolId.STATIC_RAPID.getToolId());
            }
        }
        requestDto.setTools(tools);
    }

    private void setSeverities(GenerateReportRequestDtoV3 requestDto) {
        List<String> severities = Severity.getAllAsList();
        if (!CollectionUtils.isEmpty(requestDto.getSeverities())) {
            severities = requestDto.getSeverities().stream().map(String::toLowerCase).collect(Collectors.toList());
        }
        requestDto.setSeverities(severities);
    }

    @Transactional(readOnly = true)
    public Page<Reports> getAllReports(String filter, String organizationID, String userID, Integer offset, Integer limit, String sortBy) {
        log.debug("Get all reports record  with limit: {}, offset: {}, filter: {}, sort: {}.", limit, offset, filter, sortBy);

        CommonRSQLValidator.validateQueryParams(filter, sortBy, offset, limit, VALID_REPORTS_FILTERS, VALID_SORT_BY_REPORTS_PARAMETERS);
        String filterForQuery = buildFilterForQuery(filter, organizationID, userID);
        Pageable pageable = PaginationUtil.getPageable(offset, limit);
        Page<Reports> reportsPage = getReportsItemsByRSQLFilterCriteria(filterForQuery, sortBy, pageable);

        return reportsPage.map(reportsModel -> new ModelMapper().map(reportsModel, (Type) Reports.class));
    }

    private Page<Reports> getReportsItemsByRSQLFilterCriteria(String filter, String sort, Pageable pageable) {
        Page<Reports> reportsModel;
        Specification<Reports> rsqlSpec = toSpecification(filter);
        reportsModel = reportsRepository.findAll(rsqlSpec.and(CommonRSQLValidator.sortSpec(sort)), pageable);
        if (reportsModel.isEmpty()) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }
        return reportsModel;
    }


    @Transactional
    public Reports getReportById(String reportID, String tenantID, UserInfo currentUser) {
        log.info("Retrieving the report by id {}", reportID);
        if (!CommonUtils.isValidUUID(reportID)) {
            throw new ValidationException(errorMessageReader.getProperty("report.validation.exception.invalid.report.id"));
        }
        Optional<Reports> reportsEntity = reportsRepository.findByIdAndOrganizationIDAndIsDeletedIsFalse(UUID.fromString(reportID), tenantID);

        if (reportsEntity.isEmpty()) {
            throw new DataNotFoundException(errorMessageReader.getProperty("failed.to.find.report.details.by.id.detail", reportID));
        }
        if (!currentUser.getId().equals(reportsEntity.get().getCreatedBy())) {
            throw new ForbiddenException(errorMessageReader.getProperty("forbidden.request.exception.detail"));
        }
        log.info("Report found for the report id {}", reportID);
        return reportsEntity.get();
    }

    @Transactional
    public void deleteReport(String reportId, String tenantId, UserInfo currentUser, String apiTokenName, String ipAddress) throws DataNotFoundException, ForbiddenException {
        log.debug("Soft deleting the report for reportId: {} tenantId: {}", reportId, tenantId);
        Validator.validateUUID(reportId, Constants.PARAM.REPORT_ID);
        Optional<Reports> reportsEntity = reportsRepository.findByIdAndOrganizationIDAndIsDeletedIsFalse(UUID.fromString(reportId), tenantId);
        try {
            if (reportsEntity.isEmpty()) {
                throw new DataNotFoundException(errorMessageReader.getProperty("failed.to.find.report.details.by.id.detail", reportId));
            }
            if (!currentUser.getId().equals(reportsEntity.get().getCreatedBy())) {
                throw new ForbiddenException(errorMessageReader.getProperty("forbidden.request.exception.detail"));
            }
            if (reportsEntity.get().getStatus().equals(ReportStatus.IN_PROGRESS.toString())
                || reportsEntity.get().getStatus().equals(ReportStatus.INITIATED.toString())) {
                throw new MethodNotAllowedException(errorMessageReader.getProperty("report.delete.exception.report.not.ready"));
            }
            Reports reports = reportsEntity.get();
            reports.setDeleted(true);
            reportsRepository.save(reports);
            log.info("Report soft deleted successfully for report id {}", reportId);
            notificationAndAuditService.publishDeleteSuccessEvent(tenantId, apiTokenName, ipAddress,
                currentUser, ReportType.fromType(reportsEntity.get().getReportType()), serviceTokenUtil.getToken(tenantId), reportsEntity.get());
        } catch (DataNotFoundException | ReportGeneratorException | ForbiddenException exception) {
            Reports reportsEntry = reportsEntity.orElseGet(() -> Reports.builder()
                .id(UUID.fromString(reportId))
                .organizationID(tenantId).build());
            notificationAndAuditService.publishDeleteFailureEvent(tenantId, apiTokenName, ipAddress,
                currentUser, null, serviceTokenUtil.getToken(tenantId), reportsEntry, exception.getMessage());
            throw exception;
        }
    }

    /**
     * creates artifact and uploads to GCS and stores the artifact id in db.
     *
     * @param outputFile -  file to be uploaded to GCS
     * @param tenantId   - organization id
     */
    public String createAndUploadArtifact(File outputFile, String tenantId, String token) {
        long startTime = System.currentTimeMillis();
        String signedUrl = null, artifactId = null;
        StorageProtos.ArtifactCreateResponse artifactCreateResponse = createArtifact(outputFile, tenantId, token);
        if (artifactCreateResponse == null || artifactCreateResponse.getSignedURL().isEmpty()) {
            log.error("Artifact creation failed due to some unknown reason");
            throw new RuntimeException(errorMessageReader.getProperty("artifact.creation.failed.unknown.reason"));
        }
        signedUrl = artifactCreateResponse.getSignedURL();
        artifactId = artifactCreateResponse.getArtifactId();
        uploadUsingSignedURL(tenantId, signedUrl, outputFile);
        consoleApiTime(startTime, "to create and upload artifact to gcs");
        return artifactId;
    }

    /**
     * Takes file as input and creates artifact via storage service and returns response containing signedUrl and artifactId
     *
     * @param outputFile - file uploaded to GCS
     * @param tenantId   - organization id
     * @return ArtifactCreateResponse
     */
    StorageProtos.ArtifactCreateResponse createArtifact(File outputFile, String tenantId, String token) {
        StorageProtos.ArtifactCreateRequest artifactCreateRequest = StorageServiceUtil.getArtifactCreateRequest(outputFile, tenantId);
        return storageManagerClient.createArtifact(artifactCreateRequest, token);
    }

    /**
     * Takes file as input and creates artifact via storage service and returns response containing signedUrl and artifactId
     *
     * @param outputFile - file uploaded to GCS
     * @param tenantId   - organization id
     * @return ArtifactCreateResponse
     */
    com.synopsys.altair.storage.service.StorageProtos.ArtifactCreateResponse createSynopsysArtifact(File outputFile, String tenantId, String token) {
        com.synopsys.altair.storage.service.StorageProtos.ArtifactCreateRequest artifactCreateRequest = StorageServiceUtil.getSynopsysArtifactCreateRequest(outputFile, tenantId);
        return storageManagerClient.createSynopsysArtifact(artifactCreateRequest, token);
    }

    /**
     * Takes signedUrl and file as input, converts file to byte stream and uploads on GCS.
     *
     * @param tenantId
     * @param signedUrl  - signed url from GCS
     * @param outputFile -  file uploaded to GCS
     */
    void uploadUsingSignedURL(String tenantId, String signedUrl, File outputFile) {
        try {
            byte[] fileArray = Files.readAllBytes(outputFile.toPath());
            gcsClient.uploadArtifactUsingSignedUrl(tenantId, signedUrl, fileArray);
        } catch (IOException e) {
            log.error("Exception occurred while reading bytes from file :{}", e.getMessage());
            throw new RuntimeException(errorMessageReader.getProperty("report.gcs.failed.to.read.file.bytes", e.getMessage()));
        }
    }

    public Reports updateReports(Reports reports, ReportStatus reportStatus, String failureReason, File reportFile) {
        reports.setStatus(reportStatus.name());
        reports.setFailureReason(failureReason);

        return updateReports(reports, reportFile);
    }

    public Reports updateReports(Reports reports, File reportFile) {
        log.debug("Trying to update the report {} status to {}", reports.getId(), reports.getName());

        if (reports.getStatus().equalsIgnoreCase(ReportStatus.COMPLETED.name())) {
            if (StringUtils.isEmpty(reports.getResourceID())) {
                log.error("Resource Id shall not be null.");
                throw new RuntimeException(errorMessageReader.getProperty("report.gcs.failed.to.upload.report"));
            }
        }
        reports.setCompletedDate(ZonedDateTime.of(LocalDateTime.now(), Constants.Common.UTC_ZONE_ID));
        if (reportFile != null && !reportFile.isDirectory()) {
            reports.setFileSize(reportFile.length());
        }
        return reportsRepository.save(reports);
    }

    /**
     * Store report entry to the database. It is the initial commit to the reports table.
     *
     * @param generateReportRequestDto - generate report request body
     * @param reportType               - type of report
     * @param tenantId                 - organization id
     * @param userInfo                 - user initiated report generation
     * @return - created report details
     */
    public Reports initiateReportEntry(GenerateReportRequestDtoV3 generateReportRequestDto, ReportType reportType,
                                       String tenantId, UserInfo userInfo) {
        return initiateReportEntry(generateReportRequestDto, reportType, tenantId, userInfo, null);
    }

    /**
     * Store report entry to the database. It is the initial commit to the reports table.
     *
     * @param generateReportRequestDto - generate report request body
     * @param reportType               - type of report
     * @param tenantId                 - organization id
     * @param userInfo                 - user initiated report generation
     * @param configuration            - configuration
     * @return - created report details
     */
    public Reports initiateReportEntry(GenerateReportRequestDtoV3 generateReportRequestDto, ReportType reportType,
                                       String tenantId, UserInfo userInfo, Configuration configuration) {
        log.info("Creating report entry for {} with application", reportType.name);

        UUID reportId = UUID.randomUUID();

        Reports reportsEntry = reportsRepository.save(Reports.builder()
            .id(reportId)
            .configurationId(configuration == null ? null : configuration.getId())
            .status(ReportStatus.INITIATED.name())
            .createdBy(userInfo.getId())
            .configuration(CommonUtils.getJsonString(generateReportRequestDto))
            .name(CommonUtils.getReportName(generateReportRequestDto, reportType, configuration))
            .startDate(ZonedDateTime.of(LocalDateTime.now(), Constants.Common.UTC_ZONE_ID))
            .reportType(reportType.name)
            .reportTypeDescription(reportType.reportName)
            .organizationID(tenantId)
            .message(Constants.Common.REPORT_GENERATION_INITIATED_SUCCESSFULLY_MESSAGE)
            .format(generateReportRequestDto.getReportFormat())
            .versionId(reportId)
            .appendDate(generateReportRequestDto.getAppendDate() != null && generateReportRequestDto.getAppendDate())
            .build());

        log.info("Creating ReportProject entry");

        //Objects.requireNonNull(generateReportRequestDto.getApplications(), "applications shall not be null");
        if (!CollectionUtils.isEmpty(generateReportRequestDto.getApplications())) {
            generateReportRequestDto.getApplications()
                .forEach(applicationDto -> {
                    if (!CollectionUtils.isEmpty(applicationDto.getProjects())) {
                        applicationDto.getProjects().forEach(project -> {
                            if (!StringUtils.isNotEmpty(applicationDto.getId()) && !StringUtils.isNotEmpty(project.getId())) {
                                ReportsProject reportsProject = ReportsProject.builder()
                                    .applicationID(UUID.fromString(applicationDto.getId()))
                                    .id(ReportsProjectID.builder()
                                        .reportID(reportsEntry.getId())
                                        .projectID(UUID.fromString(project.getId()))
                                        .build())
                                    .organizationID(tenantId)
                                    .build();
                                reportsProjectRepository.save(reportsProject);
                            }
                        });
                    }
                });
        }

        return reportsEntry;
    }

    @Transactional
    public void downloadReport(String reportID, String tenantID, HttpServletResponse response, UserInfo currentUser, String apiTokenName, String ipAddress, String hostName) throws DataNotFoundException, ForbiddenException {
        log.info("Trying to download the report {}", reportID);
        Optional<Reports> reportsEntity;
        reportsEntity = reportsRepository.findByIdAndOrganizationIDAndIsDeletedIsFalse(UUID.fromString(reportID), tenantID);
        String token = serviceTokenUtil.getToken(tenantID);
        try {
            if (reportsEntity.isEmpty()) {
                throw new DataNotFoundException(errorMessageReader.getProperty("failed.to.find.report.details.by.id.detail", reportID));
            }
            if (!currentUser.getId().equals(reportsEntity.get().getCreatedBy())) {
                throw new ForbiddenException(errorMessageReader.getProperty("forbidden.request.exception.detail"));
            }
            if (reportsEntity.get().getStatus().equals(ReportStatus.INITIATED.toString()) || reportsEntity.get().getStatus().equals(ReportStatus.IN_PROGRESS.toString())) {
                throw new MethodNotAllowedException(errorMessageReader.getProperty("report.delete.exception.report.not.ready"));
            }
            if (!reportsEntity.get().getStatus().equals(ReportStatus.COMPLETED.toString()) || StringUtils.isEmpty(reportsEntity.get().getResourceID())) {
                throw new DataNotFoundException(errorMessageReader.getProperty("failed.to.find.report.details.by.id.detail", reportID));
            }

            ArtifactModel artifact = getArtifact(tenantID, reportsEntity.get().getResourceID());
            if (artifact == null || artifact.getSignedURL().isEmpty()) {
                log.error("Failed to fetch Artifact data for tenantID: {}, reportID: {}", tenantID, reportID);
                throw new RuntimeException(errorMessageReader.getProperty("failed.to.fetch.artifact.detail"));
            }
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + CommonUtils.getReportNameWithExtension(reportsEntity.get().getName(), reportsEntity.get().getFormat().toLowerCase()));
            gcsClient.downloadBySignedUrl(artifact, response);
            notificationAndAuditService.publishDownloadSuccessEvent(tenantID, apiTokenName, ipAddress,
                currentUser, ReportType.fromType(reportsEntity.get().getReportType()), token, reportsEntity.get(), hostName);
        } catch (Exception e) {
            log.error("Failed to download report for tenant {}, reportID {}. Exception: {}", tenantID, reportID, e.getMessage(), e);
            Reports reportsEntry = reportsEntity.orElseGet(() -> Reports.builder()
                .id(UUID.fromString(reportID))
                .organizationID(tenantID).build());
            notificationAndAuditService.publishDownloadFailureEvent(tenantID, apiTokenName, ipAddress, hostName,
                currentUser, null, token, reportsEntry, e.getMessage());
            throw e;
        }
    }

    public ArtifactModel getArtifact(String tenantId, String artifactId) {
        String token = serviceTokenUtil.getToken(tenantId);
        log.info("Fetching Artifact from storageManager for tenant : {}.", tenantId);
        return storageManagerClient.getArtifact(tenantId, artifactId, token);
    }

    @Transactional
    public void deleteTenantDataFromDB(@NotNull String tenantId) {
        log.info("Started deleting reports data for tenant : {} from DB.", tenantId);
        try {
            reportsProjectRepository.deleteAllByOrganizationID(tenantId);
            reportsRepository.deleteAllByOrganizationID(tenantId);
        } catch (Exception e) {
            log.error("Failed to delete reports data from the database for the organization {}. Reason : {}", tenantId, e.getMessage());
            throw new TenantDataDeletionException(errorMessageReader.getProperty("tenant.data.deletion.exception.reports", tenantId, e.getMessage()));
        }
        log.info("Reports data deletion completed for tenant : {} from DB.", tenantId);
    }

    public Page<ReportTypeDTO> getSupportedReportTypes(Integer limit, Integer offset) {
        log.info("Get all supported report types");
        Validator.validateOffsetAndLimit(offset, limit);
        Pageable pageable = PaginationUtil.getPageable(offset, limit);

        List<ReportTypeDTO> reportTypes = getReportTypesByGroup()
            .entrySet()
            .stream()
            .flatMap(reportTypeList -> reportTypeList.getValue().stream())
            .map(reportTypeData -> ReportTypeDTO.builder()
                .reportType(reportTypeData.getReportType())
                .description(reportTypeData.getDescription()).build())
            .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), reportTypes.size());
        if (start > reportTypes.size()) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }
        return new PageImpl<>(reportTypes.subList(start, end), pageable, reportTypes.size());
    }

    /**
     * Get SupportedReportType with groupName
     *
     * @param limit
     * @param offset
     * @return - supported ReportTypes
     */
    public Page<GroupReportTypeDTO> getSupportedReportTypesByGroup(Integer limit, Integer offset) {
        log.info("Get all supported report types");
        Validator.validateOffsetAndLimit(offset, limit);
        Pageable pageable = PaginationUtil.getPageable(offset, limit);
        List<GroupReportTypeDTO> reportTypes = getReportTypesByGroup()
            .entrySet()
            .stream()
            .map(reportTypeList ->
                GroupReportTypeDTO
                    .builder()
                    .group(reportTypeList.getKey())
                    .reports(reportTypeList.getValue())
                    .build())
            .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), reportTypes.size());
        if (start > reportTypes.size()) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }
        return new PageImpl<>(reportTypes.subList(start, end), pageable, reportTypes.size());
    }

    /**
     * Get reportTypes by group
     *
     * @return - supported ReportTypes
     */
    private Map<String, List<ReportTypeData>> getReportTypesByGroup() {
        Map<String, List<ReportTypeData>> reportTypeData = ReportType.getReportTypes()
            .stream()
            .collect(Collectors.groupingBy(ReportTypeData::getReportTypeGroup));
        return reportTypeData;

    }

    @Transactional(readOnly = true)
    public List<Reports> getAllReportsByEmptyReportTypeDescription() {
        log.debug("Get all reports record  with empty report type description.");

        return reportsRepository.findAllByReportTypeDescriptionIsNull();
    }

    @Transactional(readOnly = true)
    public Reports updateReport(Reports report) {
        log.debug("Trying to update the report", report.getId());
        return reportsRepository.save(report);
    }

    /**
     * Generates and exports a report based on the provided configuration ID.
     *
     * @param configurationId the ID of the configuration to use for generating the report
     * @param tenantId        the ID of the tenant organization
     * @param userInfo        the user information of the person initiating the report generation
     */
    public void reportGenerationWithConfigurationId(String configurationId, String tenantId, UserInfo userInfo) {
        GenerateReportRequestDtoV2 requestDto;
        GenerateReportRequestDtoV3 requestDtoV3;
        Configuration configuration = configurationService.getConfigurationEntity(UUID.fromString(configurationId), UUID.fromString(tenantId), userInfo.getId());
        try {
            requestDto = objectMapper.readValue(configuration.getConfiguration(), GenerateReportRequestDtoV2.class);
            requestDtoV3 = CommonUtils.convertRequestDtoV2ToV3(requestDto);
        } catch (JsonProcessingException e) {
            try {
                requestDtoV3 = objectMapper.readValue(configuration.getConfiguration(), GenerateReportRequestDtoV3.class);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(errorMessageReader.getProperty("report.configuration.parser.exception.invalid.configuration", ex.getMessage()));
            }
        }
        String contentType = Constants.MEDIA_TYPE.CONFIGURATION_MEDIA_TYPE;
        if (StringUtils.isNotBlank(requestDtoV3.getScope()) && featureFlagService.isBranchSupportEnabled()) {
            contentType = Constants.MEDIA_TYPE.CONFIGURATION_MEDIA_TYPE_V2;
        }
        generateAndExport(configuration.getReportType(), requestDtoV3, configuration, serviceTokenUtil.getToken(tenantId), tenantId, userInfo, null, this.rootDomainUrl, null, contentType);
    }

    /**
     * Authorizes the report generation request by validating the provided applications against the CIAM service.
     */
    public void authorize(String token, List<ApplicationDtoV3> applications, ReportType reportType, GenerateReportRequestDtoV3 requestDto, Configuration configuration, String tenantId, UserInfo userInfo, String apiTokenName) {
        var requestedApplicationIds = applications.stream().map(ApplicationDtoV3::getId).toList();

        long startTime = System.currentTimeMillis();
        var applicationIdsFromCiam = ciamService.getApplicationsWithScope(userInfo.getId(), tenantId, CIAMScope.REPORT_RUN.getScope());
        CommonUtils.consoleApiTime(startTime, "to complete applications scope API");

        if (new HashSet<>(applicationIdsFromCiam).containsAll(requestedApplicationIds)) {
            return;
        }

        Reports reportsEntry = Reports.builder().reportType(reportType.name).organizationID(tenantId).format(requestDto.getReportFormat()).name(CommonUtils.getReportName(requestDto, reportType, configuration)).build();
        String failureReason = Constants.ExceptionMessages.REPORT_FORBIDDEN_REQUEST_EXCEPTION;
        if (configuration != null) {
            failureReason = failureReason + Constants.Common.EDIT_SAVE_CONFIGURATION_MESSAGE;
        }
        notificationAndAuditService.publishFailureNotification(tenantId, apiTokenName, "system", userInfo, reportType,
            token, null, reportsEntry, HttpStatus.FORBIDDEN + ": " + failureReason, requestDto, rootDomainUrl);
        throw new ForbiddenException(failureReason);
    }

    /**
     * Validates the SBOM report generation request.
     */
    public void validateSBOM(Reports reportsEntry, GenerateReportRequestDtoV3 requestDto, ReportType reportTypeEnum, String token, Configuration configuration, String tenantId, UserInfo userInfo, String apiTokenName, String clientIP) {
        List<String> errorMessageList = new ArrayList<>();
        String reportFormat = SPDX_FORMAT;
        // For SPDX only Project validation is required
        Validator.validateProject(requestDto.getApplications(), errorMessageList);
        Validator.validateReportName(requestDto.getReportName(), errorMessageList);
        Validator.validateReportFormat(requestDto.getReportFormat(), reportTypeEnum, errorMessageList);
        Validator.validateScaTools(requestDto.getTools(), errorMessageList);
        if (!errorMessageList.isEmpty()) {
            String errorMessage = String.join("\n", errorMessageList);
            reportsEntry.setStatus(ReportStatus.FAILED.name());
            reportsEntry.setFailureReason(errorMessage);
            reportsEntry.setMessage(Constants.ExceptionMessages.VALIDATION_ERROR);
            updateReports(reportsEntry, null);
            Reports reportsEntryForAudit = Reports.builder().reportType(reportTypeEnum.name).organizationID(tenantId).format(reportFormat).name(CommonUtils.getReportName(requestDto, reportTypeEnum, configuration)).build();
            notificationAndAuditService.publishFailureNotification(tenantId, apiTokenName, clientIP, userInfo, reportTypeEnum, token,
                null, reportsEntryForAudit, String.format(Constants.ExceptionMessages.REPORT_GENERATION_AUDIT_STATUS_TEMPLATE, HttpStatus.BAD_REQUEST.value(), Constants.ExceptionMessages.BAD_REQUEST_EXCEPTION_TITLE, "Reason: " + errorMessage),
                requestDto, rootDomainUrl);
            throw new ValidationException(errorMessage);
        }
    }

    /**
     * Validates the Non SBOM report generation request.
     */
    public void validateNonSBOM(Reports reportsEntry, GenerateReportRequestDtoV3 requestDto, ReportType reportTypeEnum, String token, Configuration configuration, String tenantId, UserInfo userInfo, String apiTokenName, String clientIP, String contentType) {
        try {
            Validator.validateGenerateReportRequestData(requestDto, bigQueryService, reportTypeEnum, featureFlagService.isBranchSupportEnabled(), contentType, configuration);
        } catch (ValidationException e) {
            reportsEntry.setStatus(ReportStatus.FAILED.name());
            reportsEntry.setFailureReason(e.getMessage());
            reportsEntry.setMessage(Constants.ExceptionMessages.VALIDATION_ERROR);
            reportsEntry.setConfiguration(CommonUtils.getJsonString(requestDto));
            updateReports(reportsEntry, null);
            updateRequestDtoWithPortfolioDetailsAsync(requestDto, reportsEntry, tenantId);
            Reports reportsEntryForAudit = Reports.builder().reportType(reportTypeEnum.name).organizationID(tenantId).format(requestDto.getReportFormat()).name(CommonUtils.getReportName(requestDto, reportTypeEnum, configuration)).build();
            notificationAndAuditService.publishFailureNotification(tenantId, apiTokenName, clientIP, userInfo, reportTypeEnum, token, null,
                reportsEntryForAudit, String.format(Constants.ExceptionMessages.REPORT_GENERATION_AUDIT_STATUS_TEMPLATE, HttpStatus.BAD_REQUEST.value(), Constants.ExceptionMessages.BAD_REQUEST_EXCEPTION_TITLE, "Reason: " + e.getMessage()),
                requestDto, rootDomainUrl);
            throw e;
        }
    }

    /**
     * Combined method for generate and export
     *
     * @return - Reports
     */
    public Reports generateAndExport(String reportType, GenerateReportRequestDtoV3 requestDto, Configuration configuration, String token, String tenantId, UserInfo userInfo, String apiTokenName, String hostName, String clientIP, String contentType) {
        if (!featureFlagService.isReportServiceEnabled()) {
            throw new MethodNotFoundException(errorMessageReader.getProperty("method.not.found.exception.title"));
        }
        ReportType reportTypeEnum = ReportType.fromType(reportType);
        setFormat(requestDto, reportTypeEnum);
        Reports reportsEntry = initiateReportEntry(requestDto, reportTypeEnum, tenantId, userInfo, configuration);
        DefaultConfiguration.load(requestDto, reportTypeEnum);
        if (reportTypeEnum.isBOM) {
            validateSBOM(reportsEntry, requestDto, reportTypeEnum, token, configuration, tenantId, userInfo, apiTokenName, clientIP);
        } else {
            validateNonSBOM(reportsEntry, requestDto, reportTypeEnum, token, configuration, tenantId, userInfo, apiTokenName, clientIP, contentType);
            //Standard name will be updated in the requestDTO in the validateStandard method
            reportsEntry.setConfiguration(CommonUtils.getJsonString(requestDto));
            updateReports(reportsEntry, null);
        }

        List<Reports> reports = reportsRepository.findByCreatedByAndOrganizationIDAndStatusNotInAndIsDeletedIsFalse(userInfo.getId(), tenantId, List.of(ReportStatus.FAILED.name()));
        if (!CollectionUtils.isEmpty(reports) && reports.size() >= allowedMaxReportPerUser) {
            String errorMessage = String.format(Constants.ExceptionMessages.ALLOWED_MAX_REPORT_PER_USER, allowedMaxReportPerUser);
            log.warn("User having id {}, email {} reached maximum limit. Error : {}", userInfo.getId(), userInfo.getEmailId(), errorMessage);
            if (reportsEntry != null) {
                reportsEntry.setStatus(ReportStatus.FAILED.name());
                reportsEntry.setFailureReason(errorMessage);
                reportsEntry.setMessage(errorMessage);
                updateReports(reportsEntry, null);
            }
            throw new ReportLimitException(errorMessageReader.getProperty("report.generation.forbidden.max.limit", allowedMaxReportPerUser));
        }

        String projectID = null;
        PortfolioSubItem portfolioSubItem = null;
        ApplicationDtoV3 applicationDto = null;
        try {
            if (reportTypeEnum.isBOM) {
                applicationDto = requestDto.getApplications().get(0);
                projectID = requestDto.getApplications().get(0).getProjects().get(0).getId();
                portfolioSubItem = portfolioService.getPortfolioProjectsById(projectID, tenantId, token);
                if (StringUtils.isEmpty(applicationDto.getId())) {
                    applicationDto.setId(portfolioSubItem.getPortfolioItemId().toString());
                }
            }

            if (!featureFlagService.isBranchSupportEnabled() || reportTypeEnum.isBOM || !(contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.REPORT_POLARIS_INSIGHTS_MEDIA_TYPE_V2) || contentType.equalsIgnoreCase(Constants.MEDIA_TYPE.CONFIGURATION_MEDIA_TYPE_V2))) {
                authorize(token, requestDto.getApplications(), reportTypeEnum, requestDto, configuration, tenantId, userInfo, apiTokenName);
            }
        } catch (ForbiddenException e) {
            reportsEntry.setStatus(ReportStatus.FAILED.name());
            reportsEntry.setFailureReason(e.getMessage());
            reportsEntry.setMessage(Constants.ExceptionMessages.UNAUTHORIZED_REQUEST_EXCEPTION_TITLE);
            updateReports(reportsEntry, null);
            updateRequestDtoWithPortfolioDetailsAsync(requestDto, reportsEntry, tenantId);
            throw e;
        } catch (DataNotFoundException e) {
            String errorMessage = String.format(Constants.ExceptionMessages.PROJECT_NOT_FOUND, projectID);
            handleReportServiceFailure(reportsEntry, requestDto, tenantId, e.getMessage(), errorMessage);
            throw new DataNotFoundException(errorMessage);
        } catch (Exception e) {
            String errorMessage = Constants.ExceptionMessages.REPORT_GENERATION_FAILURE_MESSAGE;
            handleReportServiceFailure(reportsEntry, requestDto, tenantId, e.getMessage(), errorMessage);
            throw new RuntimeException(errorMessage, e);
        }
        if (reportTypeEnum.isBOM) {
            applicationDto = requestDto.getApplications().get(0);
            if (portfolioSubItem == null || portfolioSubItem.getPortfolioItemId() == null || !applicationDto.getId().equals(portfolioSubItem.getPortfolioItemId().toString())) {
                String errorMessage = String.format(Constants.ExceptionMessages.PROJECT_DOES_NOT_BELONG_TO_APPLICATION, projectID, applicationDto.getId());
                reportsEntry.setStatus(ReportStatus.FAILED.name());
                reportsEntry.setFailureReason(errorMessage);
                reportsEntry.setMessage(Constants.ExceptionMessages.VALIDATION_ERROR);
                updateReports(reportsEntry, null);
                throw new ValidationException(errorMessage);
            }
            // Set ProjectName fetched from portfolioService to projectDto
            ProjectDto projectDto = ProjectDto.builder().id(projectID).name(portfolioSubItem.getName()).defaultBranch(Branch.builder().id(portfolioSubItem.getDefaultBranch().getId()).name(portfolioSubItem.getDefaultBranch().getName()).build()).build();
            applicationDto.setProjects(List.of(projectDto));
        }
        CompletableFuture.runAsync(() -> {
            beginReportGenerationWorkflow(reportsEntry, requestDto, reportTypeEnum, requestDto.getReportFormat(), tenantId, userInfo, apiTokenName, clientIP, hostName, contentType, configuration);
        });
        return reportsEntry;
    }

    private void updateRequestDtoWithPortfolioDetailsAsync(GenerateReportRequestDtoV3 requestDto, Reports reportsEntry, String tenantId) {
        CompletableFuture.runAsync(() -> {
            configurationService.updateRequestDtoWithPortfolioDetails(requestDto, reportsEntry, tenantId);
        });
    }

    /**
     * Handles failures encountered when retrieving portfolio or project details during report generation.
     *
     * @param reportsEntry  the report entry to update
     * @param requestDto    the report request DTO being processed
     * @param tenantId      the tenant (organization) ID
     * @param failureReason the reason for the failure
     * @param errorMessage  error Message for report generation failure
     */
    private void handleReportServiceFailure(Reports reportsEntry, GenerateReportRequestDtoV3 requestDto, String tenantId, String failureReason, String errorMessage) {
        reportsEntry.setStatus(ReportStatus.FAILED.name());
        reportsEntry.setFailureReason(failureReason);
        reportsEntry.setMessage(errorMessage);
        updateReports(reportsEntry, null);
        updateRequestDtoWithPortfolioDetailsAsync(requestDto, reportsEntry, tenantId);
    }

    /**
     * Set the default format for all the reports
     *
     * @param requestDtoV2
     * @param reportType
     */
    private static void setFormat(GenerateReportRequestDtoV3 requestDtoV2, ReportType reportType) {
        if (StringUtils.isBlank(requestDtoV2.getReportFormat())) {
            if (reportType.isBOM) {
                requestDtoV2.setReportFormat(SPDX_FORMAT);
            } else {
                requestDtoV2.setReportFormat(REPORT_FORMAT);
            }
        }
    }

    /**
     * Method to run the report. This can be used to run both Sbom and other type of report.
     *
     * @param reportType
     * @param requestDto
     * @param configuration
     * @param apiVersion   the API version (e.g., "V1", "V2", "V3")
     * @param contentType  the content type of the request (e.g., "application/json")
     * @return
     */
    public ResponseEntity run(String reportType, GenerateReportRequestDtoV3 requestDto, Configuration configuration, String apiVersion, String contentType) {
        ReportType reportTypeEnum = ReportType.fromType(reportType);
        String token = TenantAwareContext.getUserInfo().getToken();
        if (!reportTypeEnum.isBOM && "V1".equalsIgnoreCase(apiVersion)) {
            // These report types are not supported in API version V1
            if (UNSUPPORTED_V1_REPORT_TYPES.contains(reportType.toLowerCase())) {
                throw new InvalidReportTypeException(errorMessageReader.getProperty("report.generation.failure.invalid.report.type", reportType));
            }
        }
        Reports reportsEntry = generateAndExport(reportType, requestDto, configuration, token, TenantAwareContext.getTenantId().toString(), TenantAwareContext.getUserInfo(), TenantAwareContext.getApiTokenName(), TenantAwareContext.getHostName(), TenantAwareContext.getIpAddress(), contentType);
        AltairLink self = null;
        ReportDTO reportDTO = null;
        if (!"V1".equalsIgnoreCase(apiVersion)) {
            self = AltairLink.from(Link.of(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + Constants.APIEndPoints.REPORT_API_ROOT_ENDPOINT + reportsEntry.getId(), IanaLinkRelations.SELF));
            reportDTO = new ModelMapper().map(reportsEntry, (Type) ReportDTO.class);
        }
        if ("V2".equalsIgnoreCase(apiVersion)) {
            if (null != reportDTO && null != reportDTO.getConfiguration()) {
                String config = reportDTO.getConfiguration();
                GenerateReportRequestDtoV3 requestDtoV3 = null;
                try {
                    requestDtoV3 = objectMapper.readValue(config, GenerateReportRequestDtoV3.class);
                    GenerateReportRequestDtoV2 reportRequestDtoV2 = CommonUtils.convertToV2(requestDtoV3);
                    reportDTO.setConfiguration(objectMapper.writeValueAsString(reportRequestDtoV2));
                } catch (JsonProcessingException e) {
                    log.error("Error while parsing configuration", e);
                    throw new RuntimeException(errorMessageReader.getProperty("report.generation.failure.configuration.parse.error"), e);
                }
            }
            return AltairResponseEntity.from(contentType, AltairSingleResponse.of(reportDTO, Arrays.asList(self)));
        } else if ("V3".equalsIgnoreCase(apiVersion)) {
            return AltairResponseEntity.from(contentType, AltairSingleResponse.of(reportDTO, Arrays.asList(self)));
        } else {
            return AltairResponseEntity.from(contentType, AltairSingleResponse.of(Constants.Common.REPORT_GENERATION_INITIATED_SUCCESSFULLY_MESSAGE));
        }
    }

    public ResponseEntity generateReportProc(HttpServletRequest request, String reportType, GenerateReportRequestDto generateReportRequestDto, String reportFormat, String reportName) {
        if (ReportType.SPDX.name.equals(reportType) || ReportType.CYCLONEDX.name.equals(reportType) || ReportType.CYCLONEDX_V1_6.name.equals(reportType)) {
            throw new InvalidReportTypeException(errorMessageReader.getProperty("report.generation.failure.invalid.report.type", reportType));
        }
        GenerateReportRequestDtoV3 generateReportRequestDtoV3 = CommonUtils.convertRequestDtoToV3(generateReportRequestDto, reportFormat, reportName);
        return run(reportType, generateReportRequestDtoV3, null, "V1", request.getContentType());
    }

    public ResponseEntity generateReportV2Proc(HttpServletRequest request, String reportType, GenerateReportRequestDtoV3 requestDtoV2) {
        if (ReportType.SPDX.name.equals(reportType) || ReportType.CYCLONEDX.name.equals(reportType) || ReportType.CYCLONEDX_V1_6.name.equals(reportType)) {
            throw new InvalidReportTypeException(errorMessageReader.getProperty("report.generation.failure.invalid.report.type", reportType));
        }
        return run(reportType, requestDtoV2, null, "V2", request.getContentType());
    }
}
