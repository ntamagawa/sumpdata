package com.example.sumpdata.rest;

import com.example.sumpdata.data.DataEntry;
import com.example.sumpdata.data.DataEntryService;
import com.example.sumpdata.data.InvalidCSVFilenameException;
import com.example.sumpdata.rest.model.FileStatus;
import com.example.sumpdata.rest.model.UploadStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping(path = "devices/{device}/entries")
@CacheConfig(cacheNames = {"controller"})
public class DataEntryController {

    Logger logger = LoggerFactory.getLogger(DataEntryController.class);

    @Autowired
    private DataEntryService dataEntryService;

    @Operation(summary = "Returns data entries for the specified date.")
    @GetMapping("/{year}/{month}/{day}")
    @Cacheable
    public List<DataEntry> getEntriesByDate(@PathVariable int device, @PathVariable int year, @PathVariable int month, @PathVariable int day,
                                            @RequestParam(required = false, defaultValue = "true") Boolean ascending) {
        // Convert the path variables into range of LocalDateTime
        LocalDateTime start = LocalDateTime.of(year, month, day, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(year, month, day, 23, 59, 59);
        return dataEntryService.retrieveInRange(device, start, end, ascending);
    }

    @Operation(summary = "Returns all data entries for the specified month.", description = "This could be a time consuming call as it returns all data entries for a month. " +
            "The normal use case assumes one data entry per minute, that means this endpoint would return 60 x 24 x 30 = 43200 data entries. Use with caution.")
    @GetMapping("/{year}/{month}")
    @Cacheable
    public List<DataEntry> getEntriesByMonth(@PathVariable int device, @PathVariable int year, @PathVariable int month,
                                             @RequestParam(required = false, defaultValue = "true") boolean ascending) {
        // Convert the path variables into range of LocalDateTime
        LocalDateTime start = LocalDateTime.of(year, month, 1, 0, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(year, month, YearMonth.of(year, month).lengthOfMonth(), 23, 59, 59);
        return dataEntryService.retrieveInRange(device, start, end, ascending);
    }


    @Operation(summary = "Add one data entry with query parameters",
            description = "Add or update a single data entry for the specified device.")
    @PostMapping(path = "")
    public @ResponseBody DataEntry addDataEntry(@PathVariable Integer device,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime measuredOn,
                                                @RequestParam String value) {
        return dataEntryService.add(device, measuredOn, value);
    }

    // TODO: DataEntry object has deviceID in it. Consider refactoring to avoid a situation where path variable's
    //       device id doesn't agree with one in the DataEntry.
    @Operation(summary = "Add one data entry as a JSON object.",
            description = "Add or update a single data entry for the specified device with the specified JSON."
    )
    @PostMapping(path = "json", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public @ResponseBody DataEntry addDataEntryJson(@PathVariable Integer device, @RequestBody DataEntry entry) {
        return dataEntryService.add(entry);
    }

    @Operation(summary = "Upload data entries in bulk from file(s)",
            description = "This endpoint takes multipart file upload. The filename has to be RaspiSump CSV convention, " +
                    "which is waterlevel-yyyyMMdd.csv format. \n\n" +
                    "Note: Swagger UI \"Try it out\" doesn't work with this endpoint as it can't properly set MultipartFile compatible request params.\n" +
                    "See the following sample curl command to try it out. This sample uploads 2 files at once.\n" +
                    "```shell\n" +
                    "curl 'http://localhost:8080/devices/2/entries/files' -X POST -F files=@waterlevel-20230805.csv -F files=@waterlevel-20230806.csv\n" +
                    "```")
    @PostMapping(path = "files", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public @ResponseBody UploadStatus uploadDataEntryFile(@PathVariable Integer device,
                                                          @RequestParam("files") MultipartFile[] files) {
        UploadStatus uploadStatus = new UploadStatus();
        uploadStatus.setNumFiles(files.length);
        boolean isSuccess = true;
        int totalNumEntries = 0;
        for (int i = 0; i < files.length; i++) {
            FileStatus fileStatus = new FileStatus(files[i].getOriginalFilename());
            try {
                int numEntries = dataEntryService.processCSV(device, files[i].getInputStream(), files[i].getOriginalFilename());
                // TODO: Consider better status reporting for each file
                fileStatus.setSuccess(true);
                fileStatus.setNumEntries(numEntries);
                totalNumEntries += numEntries;
            } catch (InvalidCSVFilenameException | IOException e) {
                isSuccess = false;
                fileStatus.setError(e.getMessage());
                logger.warn("DeviceID = " + device + ", error=" + e.getMessage());
            }
            uploadStatus.addFileStatus(fileStatus);
        }

        uploadStatus.setTotalEntries(totalNumEntries);
        uploadStatus.setSuccess(isSuccess);
        return uploadStatus;

    }

}
