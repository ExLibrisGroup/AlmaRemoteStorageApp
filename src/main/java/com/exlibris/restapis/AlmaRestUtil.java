package com.exlibris.restapis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.apache.log4j.Logger;
import org.json.JSONObject;

public class AlmaRestUtil {

    final private static Logger logger = Logger.getLogger(AlmaRestUtil.class);
    
    public static final int PER_SECOND_THRESHOLD = 429;
    public static final int ERROR_WINHTTP_SECURE_FAILURE = 600;
    public static final int MAX_RETRIES = 3;
    
    public static HttpResponse sendHttpReq(String url, String method, String body) {
    	int counter = 0;
    	HttpResponse httpResponse = null;
    	int code = HttpsURLConnection.HTTP_INTERNAL_ERROR;
        while (code >= HttpsURLConnection.HTTP_INTERNAL_ERROR && code < ERROR_WINHTTP_SECURE_FAILURE
                && code != PER_SECOND_THRESHOLD) {
                httpResponse = sendReq(url,method, body, counter);
                if (httpResponse != null) {
                    code = httpResponse.getResponseCode();
                }
                if (code >= HttpsURLConnection.HTTP_INTERNAL_ERROR && code <= HttpsURLConnection.HTTP_VERSION
                        && code != PER_SECOND_THRESHOLD) {
                	if(counter >= MAX_RETRIES) {
                    	logger.error("Response Code " + code + ". Thread not sleeping for 5 minutes anymore.");
                    	return httpResponse;
                    }
                    logger.info("Response Code " + code + ". Thread sleeping for 5 minutes.");
                    counter ++;
                    try {
						TimeUnit.MINUTES.sleep(5);
					} catch (InterruptedException e) {
						logger.warn("Retry sleep interrupted. Aborting further retries for URL.");
						Thread.currentThread().interrupt();
						return httpResponse;
					}
                    
                }
        }
        return httpResponse;
    }
    

    public static HttpResponse sendReq(String url, String method, String body, int retry) {
		if(retry > 0 ){
			url = url + (url.contains("?") ? "&" : "?") + "retry=" + retry;
		}
		String maskedUrl = url.replaceAll("(apikey=)([^&]{0,})([^&]{4})(?=(&|$))", "$1notOnLog....$3");
		logger.info("Sending " + method + " request to URL : " + maskedUrl);
        HttpURLConnection con = null;
        try {
            URL obj = new URL(url);
            con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod(method);
            con.setConnectTimeout(20000); //set timeout to 20 seconds
            con.setReadTimeout(60000);
            con.setRequestProperty("Accept", "application/json");
            if (body != null) {
                try {
                    new JSONObject(body);
                    con.setRequestProperty("Content-Type", "application/json");
                } catch (Exception e) {
                    con.setRequestProperty("Content-Type", "application/xml");
                }
                con.setDoOutput(true);
                try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(con.getOutputStream(), "UTF-8"))) {
                    bw.write(body);
                    bw.flush();
                }
            }

            logger.info("Response Code : " + con.getResponseCode());

            BufferedReader in = null;
            if (con.getErrorStream() != null) {
                // logger.error("reading con.getErrorStream()...");
                in = new BufferedReader(new InputStreamReader(con.getErrorStream()));
            } else {
                try {
                    in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                } catch (Exception e) {
                    logger.info("Can't get Input Stream" + con.getResponseCode());
                }
            }
            String inputLine;
            StringBuffer response = new StringBuffer();

            if (in != null) {
                try {
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                        response.append(System.lineSeparator());
                    }
                } finally {
                    in.close();
                }
            }
            String out = response.toString().trim();

            // Log output of PUT only on error. Always log output of GET.
            if (con.getResponseCode() >= HttpsURLConnection.HTTP_OK
                    && con.getResponseCode() <= HttpsURLConnection.HTTP_MULT_CHOICE && method != "PUT") {
                logger.info("output: " + out);
            } else {
                try {
                    JSONObject jsonErrror = new JSONObject(out);
                    out = jsonErrror.getJSONObject("errorList").getJSONArray("error").getJSONObject(0)
                            .getString("errorMessage");
                    logger.info("message: " + out);
                } catch (Exception e) {
                    logger.info("message: " + out);
                    out = "Failed Sending " + method + " request to URL - Response Code : " + con.getResponseCode();
                }
            }

            HttpResponse responseObj = new HttpResponse(out, con.getHeaderFields(), con.getResponseCode());

            return responseObj;
        } catch (Exception e) {
			logger.error("Failed getting HttpResponse : " + e.getMessage(),e);
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

}