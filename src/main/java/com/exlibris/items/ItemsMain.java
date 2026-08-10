package com.exlibris.items;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Record;
import org.marc4j.marc.VariableField;

import com.exlibris.configuration.ConfigurationHandler;
import com.exlibris.ftp.FTPClient;
import com.exlibris.ftp.FTPUtil;
import com.exlibris.ftp.SFTPUtil;
import com.exlibris.logger.ReportUtil;
import com.exlibris.util.XmlUtil;

class Task implements Runnable {

    File xmlFile;
    String institution;
    Map<String, Integer> fileCounter;

    public Task(File xmlFile, String institution, Map<String, Integer> fileCounter) {
        this.xmlFile = xmlFile;
        this.institution = institution;
        this.fileCounter = fileCounter;
    }

    public void run() {
        ItemsMain.hendleFile(xmlFile, institution, fileCounter);
    }
}

public class ItemsMain {

    final static String MAINLOCALFOLDER = "files/items/";
    static int maxNumberOfThreds = 4;

    final static Logger logger = Logger.getLogger(ItemsMain.class);

    public static synchronized void mergeItemsWithSCF(String institution) {
        FTPClient ftpUtil;

        try {
            JSONObject props = ConfigurationHandler.getInstance().getConfiguration();
            String ftpFolder = props.getJSONObject("ftp_server").getString("main_folder");
            String ftpPort = props.getJSONObject("ftp_server").getString("ftp_port");
            String mainLocalFolder = MAINLOCALFOLDER;
            if (props.has("main_local_folder") && props.get("main_local_folder") != null) {
                mainLocalFolder = props.getString("main_local_folder") + "/" + MAINLOCALFOLDER;
            }
            if (props.has("max_number_of_threads") && props.get("max_number_of_threads") != null) {
                maxNumberOfThreds = Integer.valueOf(props.getString("max_number_of_threads"));
            }
            boolean ignoreDeleteFiles = true;
            if (props.has("ignore_delete_files") && props.get("ignore_delete_files") != null) {
                ignoreDeleteFiles = Boolean.valueOf(props.getString("ignore_delete_files"));
            }
            logger.info("Starting Merge Items With SCF For Institution: " + institution);
            logger.debug("empty the local folder");
            File tarGzFolder = new File(mainLocalFolder + "targz/");
            if (tarGzFolder.isDirectory()) {
                FileUtils.cleanDirectory(tarGzFolder);
            } else {
                tarGzFolder.mkdirs();
            }
            logger.debug("get files from ftp");
            if (ftpPort.equals("22")) {
                ftpUtil = new SFTPUtil();
            } else {
                ftpUtil = new FTPUtil();
            }

            ftpUtil.getFiles(ftpFolder + "/" + institution + "/items/", mainLocalFolder + "targz/");

            logger.debug("loop over tar gz files and exact them to xml folder");
            XmlUtil.unTarGzFolder(tarGzFolder, mainLocalFolder + "xml/");
            logger.debug("loop over xml files and convert them to records");
            File xmlFolder = new File(mainLocalFolder + "xml/");
            File[] xmlFiles = xmlFolder.listFiles();
            ExecutorService pool = Executors.newFixedThreadPool(maxNumberOfThreds);
            Map<String, Integer> fileCounter = new HashMap<String, Integer>();
            for (File xmlFile : xmlFiles) {
                if (ignoreDeleteFiles && xmlFile.getName().toLowerCase().contains("_delete")) {
                    logger.info("Ignoring deleted xml file: " + xmlFile.getName());
                    continue;
                }
                logger.info("starting to hendle xml file: " + xmlFile.getName());
                fileCounter.put(xmlFile.getName(), 0);
                pool.execute(new Task(xmlFile, institution, fileCounter));
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
                    logger.error("Merge Items With SCF For Institution: " + institution
                            + " did not finish within 30 minutes. Forcing shutdown of remaining tasks.");
                    List<Runnable> notStarted = pool.shutdownNow();
                    for (Runnable runnable : notStarted) {
                        if (runnable instanceof Task) {
                            String fileName = ((Task) runnable).xmlFile.getName();
                            String message = "File not processed - merge timed out after 30 minutes";
                            logger.error(message + ". File: " + fileName);
                            ReportUtil.getInstance().appendReport("ItemsHandler", fileName, institution, message);
                        }
                    }
                }
            } catch (InterruptedException e) {
            }
            int totalRecords = 0;
            for (Integer count : fileCounter.values()) {
                totalRecords = totalRecords += count;
            }
            logger.info("Total Records from FTP: " + totalRecords);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static String getNetworkNumber(List<VariableField> variableFields) {
        for (VariableField variableField : variableFields) {
            String subfield = ((DataField) variableField).getSubfieldsAsString("a");
            if (subfield != null && subfield.contains("EXLNZ")) {
                return subfield.replaceAll("^\\(EXLNZ(.*)\\)", "");
            }
        }
        return null;
    }

    public static void hendleFile(File xmlFile, String institution, Map<String, Integer> fileCounter) {
        try {
            String methodName = null;
            if (xmlFile.getName().toLowerCase().contains("_delete")) {
                methodName = "itemDeleted";
            } else {
                methodName = "itemUpdated";
            }
            logger.debug("convert xml file to marc4j");
            List<Record> records = XmlUtil.xmlFileToMarc4jRecords(xmlFile);
            logger.debug("loop over records and merge with SCF");
            for (Record record : records) {
                logger.debug("get network system number");
                String NZMmsId = getNetworkNumber(record.getVariableFields("035"));
                List<VariableField> variableFields = record.getVariableFields("ITM");
                for (VariableField variableField : variableFields) {
                    ItemData itemData = ItemData.dataFieldToItemData(record.getControlNumber(),
                            (DataField) variableField, institution, NZMmsId);
                    try {
                        if (itemData.getBarcode() == null) {
                            logger.warn("Synchronize Item Failed. Barcode is null Item Data: " + variableField);
                            continue;
                        }
                        if (itemData.getNetworkNumber() == null) {
                            itemData.setRecord(record);
                        }
                        Method method = Class.forName("com.exlibris.items.ItemsHandler").getMethod(methodName,
                                ItemData.class);
                        method.invoke(null, itemData);
                    } catch (Exception e) {
                        String message = "Failed to handle item " + itemData.getBarcode();
                        ReportUtil.getInstance().appendReport("ItemsHandler", itemData.getBarcode(),
                                itemData.getInstitution(), message);
                        logger.error(message, e);
                    }
                }
            }
            fileCounter.put(xmlFile.getName(), records.size());
            logger.info("Total records from file: " + xmlFile.getName() + " :" + records.size());
        } catch (Exception e) {
            ReportUtil.getInstance().appendReport("ItemsHandler", xmlFile.getName(), institution,
                    "Failed to handle File " + xmlFile.getName());
            logger.error(e.getMessage());
        }
    }
}
