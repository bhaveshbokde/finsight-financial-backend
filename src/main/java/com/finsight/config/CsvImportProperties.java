package com.finsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Binds {@code finsight.csv-import.*}.
 *
 * @param maxFileSizeBytes    hard ceiling applied in the service layer, independent of the
 *                            servlet container's multipart limit (defence in depth)
 * @param maxRows             refuse files with more data rows than this
 * @param asyncThresholdRows  files with more rows than this are processed through Kafka
 *                            instead of inline on the request thread
 * @param allowedContentTypes MIME types a browser / curl may legitimately send for a CSV
 */
@ConfigurationProperties(prefix = "finsight.csv-import")
public record CsvImportProperties(

        @DefaultValue("5242880") long maxFileSizeBytes,
        @DefaultValue("50000") int maxRows,
        @DefaultValue("50") int asyncThresholdRows,
        @DefaultValue({"text/csv", "application/vnd.ms-excel", "text/plain", "application/octet-stream"})
        List<String> allowedContentTypes
) {
}
