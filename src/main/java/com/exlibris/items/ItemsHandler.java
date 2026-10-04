package com.exlibris.items;

import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.VariableField;

import com.exlibris.configuration.ConfigurationHandler;
import com.exlibris.logger.ReportUtil;
import com.exlibris.restapis.HttpResponse;
import com.exlibris.util.SCFUtil;
import com.exlibris.util.XmlUtil;

public class ItemsHandler {

    final private static Logger logger = Logger.getLogger(ItemsHandler.class);

    private transient static MarcFactory factory = MarcFactory.newInstance();

    public static void itemUpdated(ItemData itemData) {
        logger.info("New/Update Item. Barcode: " + itemData.getBarcode());

        JSONObject jsonItemObject = SCFUtil.getSCFItem(itemData);
        if (jsonItemObject == null) {
            logger.debug("The item does not exist in the remote Storage");
            if (!SCFUtil.isItemInRemoteStorage(itemData)) {
                return;
            } else {
                JSONObject jsonBibObject = null;
                String mmsId = null;
                String holdingId = null;
                if (itemData.getNetworkNumber() == null) {
                    logger.debug("get matching bib from SCF by Institution Code");
                    jsonBibObject = SCFUtil.getSCFBibByINST(itemData);
                    if (jsonBibObject == null) {
                        logger.debug("Missing Network Number - Can't find SCF bib - Creating Local Bib and Holding");
                        Record record = itemData.getRecord();
                        DataField df = factory.newDataField("035", ' ', ' ');
                        String localNumber = "(" + itemData.getInstitution() + ")" + itemData.getMmsId();
                        df.addSubfield(factory.newSubfield('a', localNumber));
                        record.addVariableField(df);
                        for (VariableField avaField : record.getVariableFields("AVA")) {
                            record.removeVariableField(avaField);
                        }
                        String xmlRecord = XmlUtil.recordToMarcXml(record);

                        if (xmlRecord == null) {
                            String message = "Missing Network Number - Can't find SCF bib - Can't create Local Bib and Holding - Exiting";
                            logger.error(message);
                            ReportUtil.getInstance().appendReport("ItemsHandler", itemData.getBarcode(),
                                    itemData.getInstitution(), message);
                            return;
                        }
                        jsonBibObject = SCFUtil.createSCFBibByINST(itemData, "<bib>" + xmlRecord + "</bib>");
                        if (jsonBibObject == null) {
                            String message = "The Bib does not exist in the remote Storage - Can't create bib - Exiting";
                            logger.error(message);
                            ReportUtil.getInstance().appendReport("ItemsHandler", itemData.getBarcode(),
                                    itemData.getInstitution(), message);
                            return;
                        }
                        mmsId = jsonBibObject.getString("mms_id");
                    } else {
                        logger.debug(
                                "The Bib exists in the remote Storage - Check for Holding and get Mms Id from exist SCF Bib");
                        mmsId = jsonBibObject.getJSONArray("bib").getJSONObject(0).getString("mms_id");
                        holdingId = SCFUtil.getSCFHoldingByMmsID(mmsId, itemData);
                    }
                } else {
                    logger.debug("get matching bib from SCF by NZ");
                    HttpResponse bibResponse = SCFUtil.getSCFBibByNZ(itemData);
                    try {
                        jsonBibObject = new JSONObject(bibResponse.getBody());
                        if (jsonBibObject.has("total_record_count")
                                && "0".equals(jsonBibObject.get("total_record_count").toString())) {
                            jsonBibObject = null;
                        }
                    } catch (Exception e) {
                        jsonBibObject = null;
                    }
                    if (jsonBibObject == null) {
                        logger.debug("The Bib does not exist in the remote Storage - Creating Bib and Holding");
                        jsonBibObject = SCFUtil.createSCFBibByNZ(itemData);
                        if (jsonBibObject == null) {
                            String message = "The Bib does not exist in the remote Storage - Can't create bib - Exiting";
                            logger.error(message);
                            ReportUtil.getInstance().appendReport("ItemsHandler", itemData.getBarcode(),
                                    itemData.getInstitution(), message);
                            return;
                        }
                        mmsId = jsonBibObject.getString("mms_id");
                    } else {
                        logger.debug(
                                "The Bib exists in the remote Storage - Check for Holding and get Mms Id from exist SCF Bib");
                        mmsId = jsonBibObject.getJSONArray("bib").getJSONObject(0).getString("mms_id");
                        holdingId = SCFUtil.getSCFHoldingByMmsID(mmsId, itemData);
                    }
                }
                if (holdingId == null) {
                    logger.debug("The Holdinnng does not exist in the remote Storage - Creating Holding");
                    holdingId = SCFUtil.createSCFHoldingAndGetId(jsonBibObject, mmsId, itemData);
                }
                if (holdingId != null) {
                    logger.debug("Creating Item Based SCF on mmsId and holdingId");
                    String itemPid = SCFUtil.createSCFItemAndGetId(itemData, mmsId, holdingId);
                    try {
                        TimeUnit.SECONDS.sleep(3);
                    } catch (InterruptedException e) {
                    }
                    if(itemPid != null) {
	                    logger.debug("Loan the new Item who was created");
	                    SCFUtil.createSCFLoan(itemData, itemPid);
                    }
                } else {
                    String message = "Can't create SCF holding. MMS ID : " + mmsId;
                    ReportUtil.getInstance().appendReport("ItemsHandler", itemData.getBarcode(),
                            itemData.getInstitution(), message);
                }
            }
        } else {
            logger.debug("The item exists in the remote Storage");
            if (!SCFUtil.isItemInRemoteStorage(itemData)) {
            	boolean ignoreDeleteFiles = true;
            	JSONObject props = ConfigurationHandler.getInstance().getConfiguration();
                if (props.has("ignore_delete_files") && props.get("ignore_delete_files") != null) {
                    ignoreDeleteFiles = Boolean.valueOf(props.getString("ignore_delete_files"));
                }
                if (ignoreDeleteFiles ) {
                    logger.info("Item exists in the SCF, but in the Institution it's no in a remote-storage location  - Ignoring delete from the SCF");
                }else {
	                logger.debug(
	                        "Item exists in the SCF, but in the Institution it's no in a remote-storage location need to delete it from SCF");
	                SCFUtil.deleteSCFItem(jsonItemObject, itemData);
                }
            } else {
                logger.debug("Item exists merge between INST item and SCF item");
                SCFUtil.updateSCFItem(itemData, jsonItemObject);
            }
        }
    }

    public static void itemDeleted(ItemData itemData) {
        logger.info("Deleted Item. Barcode: " + itemData.getBarcode());
        JSONObject jsonItemObject = SCFUtil.getSCFItem(itemData);
        if (jsonItemObject != null) {
            logger.debug("The item exists in the remote Storage");
            SCFUtil.deleteSCFItem(jsonItemObject, itemData);
        }

    }
}
