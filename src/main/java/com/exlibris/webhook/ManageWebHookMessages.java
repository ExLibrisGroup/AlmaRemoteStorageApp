package com.exlibris.webhook;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.exlibris.configuration.ConfigurationHandler;
import com.exlibris.items.ItemData;
import com.exlibris.items.ItemsMain;
import com.exlibris.logger.ReportUtil;
import com.exlibris.request.RequestsMain;
import com.exlibris.restapis.HttpResponse;
import com.exlibris.util.SCFUtil;

public class ManageWebHookMessages {

    public enum WebhookEvent {
        LOAN_CREATED, LOAN_RETURNED, LOAN_LOST, LOAN_CLAIMED_RETURNED, LOAN_RENEWED, LOAN_DUE_DATE;
    }

    public enum WebhooksActionType {
        JOB_END, LOAN, REQUEST;
    }

    final private static org.apache.log4j.Logger logger = org.apache.log4j.Logger
            .getLogger(ManageWebHookMessages.class);

    private static final Pattern JOB_ID_IN_LINK = Pattern.compile("/conf/jobs/([^/]+)");

    public static void getWebhookMessage(String webhookMessage) {
        if (webhookMessage.isEmpty()) {
            logger.info("message is empty");
            return;
        }
        JSONObject webhookJsonMessage = new JSONObject(webhookMessage);
        try {
            logger.info("institution is :" + webhookJsonMessage.getJSONObject("institution").get("value"));
        } catch (JSONException e) {
            logger.debug("no institution in webhook message");
        }
        logger.info("action is :" + webhookJsonMessage.get("action"));
        try {
        	if(webhookJsonMessage.has("event") && ! webhookJsonMessage.isNull("event")) {
        		logger.info("event is :" + webhookJsonMessage.getJSONObject("event").get("value"));
        	}else {
        		logger.debug("no event in webhook message");
        	}
        } catch (JSONException e) {
            logger.debug("no event in webhook message");
        }

        messageHandling(webhookJsonMessage);
    }

    public static void messageHandling(JSONObject webhookMessage) {
        String action = webhookMessage.getString("action");

        JSONObject props = ConfigurationHandler.getInstance().getConfiguration();
        String remoteStorageInstitution = props.get("remote_storage_inst").toString();

        switch (WebhooksActionType.valueOf(action)) {
        case LOAN: {
            String event = webhookMessage.getJSONObject("event").getString("value");
            if (WebhookEvent.valueOf(event).equals(WebhookEvent.LOAN_RETURNED)) {
                String webhookMessageInstitution = webhookMessage.getJSONObject("institution").getString("value");
                if (webhookMessageInstitution.equalsIgnoreCase(remoteStorageInstitution)) {
                    loanReturned(webhookMessage);
                } else {
                    logger.debug(
                            "Alma Remote Storage App Handles Loan Returned only For Remote Storage Institution. webhook institution is: "
                                    + webhookMessageInstitution);
                }

            }
            break;
        }
        case JOB_END: {
            JSONObject jobInstance = webhookMessage.getJSONObject("job_instance");
            JSONObject jobInfo = jobInstance.optJSONObject("job_info");
            String jobId = null;
            if (jobInfo != null) {
                jobId = jobInfo.optString("id", null);
            } else {
                // some JOB_END messages have no job_info - take the id from the instance link
                Matcher linkMatcher = JOB_ID_IN_LINK.matcher(jobInstance.optString("link", ""));
                if (linkMatcher.find()) {
                    jobId = linkMatcher.group(1);
                }
            }
            if (jobId == null) {
                logger.debug("JOB_END webhook message has no job id - ignoring");
                break;
            }
            String institution = getInstitutionByJobId(jobId, "publishing_job_id");
            if (institution != null) {
            	logger.debug("Merge Items With SCF. Job Id: " + jobId + " Institution: "+institution);
                ItemsMain.mergeItemsWithSCF(institution);
                return;
            }
            institution = getInstitutionByJobId(jobId, "requests_job_id");
            if (institution != null) {
                RequestsMain.sendRequestsToSCF(institution);
                return;
            }
            logger.debug("The Job does not exist in the configuration jobs list. Job Id: " + jobId);
            break;
        }
		case REQUEST: {
			String event = webhookMessage.getJSONObject("event").getString("value");
			if (event.equals("REQUEST_CANCELED")) {
				String webhookMessageInstitution = webhookMessage.getJSONObject("institution").getString("value");
				if (webhookMessageInstitution.equalsIgnoreCase(remoteStorageInstitution)) {
					if (webhookMessage.has("user_request")) {
						JSONObject userRequest = webhookMessage.getJSONObject("user_request");
						if (userRequest.has("barcode") && !JSONObject.NULL.equals(userRequest.get("barcode"))) {
							String barcode = webhookMessage.getJSONObject("user_request").getString("barcode");
							if (barcode != null && !barcode.isEmpty()) {
								if (barcode.endsWith("X")) {
									barcode = barcode.substring(0, barcode.length() - 1);
								} else {
									logger.info("Request Not Part Of SCF.  Barcode: " + barcode);
									return;
								}
								requestCanceled(barcode);
							}
						} else if (userRequest.has("comment") && !JSONObject.NULL.equals(userRequest.get("comment"))) {
							String comment = userRequest.getString("comment");
							// example : {Source Request 01WRLC_code-requestId-userPrimaryId}
							try {
								String sourceRequestDetails = comment.substring(comment.indexOf("{Source Request "));
								sourceRequestDetails = sourceRequestDetails.substring("{Source Request ".length());
								String institution = sourceRequestDetails.substring(0,sourceRequestDetails.indexOf("-"));

								String sourceRequestId = sourceRequestDetails.substring(sourceRequestDetails.indexOf("-") + 1);
								sourceRequestId = sourceRequestId.substring(0, sourceRequestId.indexOf("-"));

								String sourceRequestUser = sourceRequestDetails.substring(sourceRequestDetails.indexOf("-", sourceRequestDetails.indexOf("-") + 1) + 1);
								sourceRequestUser = sourceRequestUser.substring(0, sourceRequestUser.indexOf("}"));

								if (institution != null && !institution.isBlank() && sourceRequestId != null
										&& !sourceRequestId.isBlank() && sourceRequestUser != null
										&& !sourceRequestUser.isBlank()) {
									requestCanceledBibLevel(sourceRequestUser, sourceRequestId, institution);
								}else {
									logger.debug("Request Not Part Of SCF - Not canceling source request");
								}
							} catch (Exception e) {
								logger.debug("Request Not Part Of SCF - Not canceling source request");
								return;
							}
						} else {
							logger.debug(
									"The barcode or comment does not exist in the webhook message - Not canceling source request");
						}
					}
				}
			}
		}
        default:
            break;

        }
    }

    private static void loanReturned(JSONObject webhookMessage) {

        String userId = webhookMessage.getJSONObject("item_loan").getString("user_id");
        String barcode = webhookMessage.getJSONObject("item_loan").getString("item_barcode");
        if (barcode.endsWith("X")) {
            barcode = barcode.substring(0, barcode.length() - 1);
        } else {
            logger.info("Request Not Part Of SCF. userId: " + userId + " Barcode: " + barcode);
            return;
        }
        ItemData itemData = new ItemData(barcode);
        String institution = null;
        try {
            JSONObject jsonScfItemObject = SCFUtil.getSCFItem(itemData);
            if (jsonScfItemObject == null) {
                String message = "Can't get SCF item. Barcode : " + itemData.getBarcode();
                logger.error(message);
                ReportUtil.getInstance().appendReport("LoanReturnedHandler", itemData.getBarcode(),
                        itemData.getInstitution(), message);
            }
            institution = jsonScfItemObject.getJSONObject("item_data").getJSONObject("provenance").getString("value");
        } catch (Exception e) {
            logger.info("Can't get Request institution .Barcode: " + barcode);
            logger.info("Request Not Part Of SCF. userId: " + userId);
            return;
        }

        if (institution == null) {
            logger.info("Request Not Part Of SCF. userId: " + userId + " Barcode: " + barcode);
            return;
        }
        itemData.setInstitution(institution);
        logger.info("Request source institution is :" + institution + ". userId: " + userId);
        logger.info("Scan In Request. Source Institution Barcode: " + itemData.getBarcode());
        JSONObject jsonItemObject = SCFUtil.getINSItem(itemData);
        if (jsonItemObject == null) {
            String message = "Can't get institution : " + itemData.getInstitution() + " item. Barcode : "
                    + itemData.getBarcode();
            logger.error(message);
            ReportUtil.getInstance().appendReport("LoanReturnedHandler", itemData.getBarcode(),
                    itemData.getInstitution(), message);
            return;
        }
        SCFUtil.scanINSRequest(jsonItemObject, itemData);

    }

    private static void requestCanceled(String barcode) {
        ItemData itemData = new ItemData(barcode);
        JSONObject jsonScfItemObject = null;
        String institution = null;
        try {
            jsonScfItemObject = SCFUtil.getSCFItem(itemData);
            if (jsonScfItemObject == null) {
                String message = "Can't get SCF item . Barcode: " + barcode;
                ReportUtil.getInstance().appendReport("RequestCanceled", barcode, institution, message);
                logger.error(message);
                return;
            }
            institution = jsonScfItemObject.getJSONObject("item_data").getJSONObject("provenance").getString("value");
        } catch (Exception e) {
            logger.info("Can't get Request institution, Request Not Part Of SCF .Barcode: " + barcode);
            return;
        }
        if (institution == null) {
            logger.info("Can't get Request institution, Request Not Part Of SCF .Barcode: " + barcode);
            return;
        }
        itemData.setInstitution(institution);
        itemData.setSourceInstitution(institution);
        logger.info("Request source institution is :" + institution);
        logger.info("Cancel Request. Source Institution Barcode: " + itemData.getBarcode());
        JSONObject jsonInsItemObject = SCFUtil.getINSItem(itemData);
        if (jsonInsItemObject == null) {
            String message = "Can't get institution item. Barcode: " + barcode;
            ReportUtil.getInstance().appendReport("RequestCanceled", barcode, institution, message);
            logger.error(message);
            return;
        }
        JSONObject jsonRequestsObject = SCFUtil.getItemRequests(jsonInsItemObject, itemData);
        if (jsonRequestsObject == null) {
            String message = "Can't get institution requests. Barcode: " + barcode;
            ReportUtil.getInstance().appendReport("RequestCanceled", barcode, institution, message);
            logger.error(message);
            return;
        }
        String requestId = null;
        for (int i = 0; i < jsonRequestsObject.getJSONArray("user_request").length(); i++) {
            JSONObject request = jsonRequestsObject.getJSONArray("user_request").getJSONObject(i);
            requestId = request.getString("request_id");
            logger.info("Request id is :" + requestId);
            HttpResponse requestResponce = SCFUtil.cancelItemRequest(jsonInsItemObject, itemData, requestId,
                    "Remote storage cannot fulfill the request");
            if (requestResponce.getResponseCode() != HttpsURLConnection.HTTP_NO_CONTENT) {
                String message = "Can't cancel SCF Item Requests. Barcode : " + itemData.getBarcode();
                logger.error(message);
                ReportUtil.getInstance().appendReport("RequestCanceled", itemData.getBarcode(),
                        itemData.getInstitution(), message);
            }
        }

        if (requestId == null) {
            String message = "Can't get SCF request id . Barcode: " + barcode;
            ReportUtil.getInstance().appendReport("RequestCanceled", barcode, institution, message);
            logger.error(message);
            return;
        }
    }

    private static void requestCanceledBibLevel(String sourceRequestUser, String sourceRequestId, String institution) {
    	logger.info("Request source institution is :" + institution);
        logger.info("Cancel Request. Source Institution User: " + sourceRequestUser);
        logger.info("Cancel Request. Source Institution Id: " + sourceRequestId); 
        
        ItemData requestData = new ItemData(null);
        requestData.setSourceInstitution(institution);
        requestData.setRequestId(sourceRequestId);
        requestData.setUserId(sourceRequestUser);
        
        SCFUtil.cancelRequest(requestData);
        
		
	}
    private static String getInstitutionByJobId(String jobId, String jobType) {
        JSONObject props = ConfigurationHandler.getInstance().getConfiguration();
        JSONArray institutions = props.getJSONArray("institutions");
        for (int i = 0; i < institutions.length(); i++) {
            if (!institutions.getJSONObject(i).getString("code").equals(props.getString("remote_storage_inst"))) {
                try {
                    if (institutions.getJSONObject(i).has(jobType)
                            && institutions.getJSONObject(i).getString(jobType).equals(jobId)) {
                        return institutions.getJSONObject(i).getString("code");
                    }
                } catch (JSONException e) {
                    logger.debug("there isn't job Id configuration for Type: " + jobType);
                    return null;
                }
            }
        }
        return null;
    }
}
