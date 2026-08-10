package com.exlibris.ftp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.exlibris.configuration.ConfigurationHandler;

public class FTPUtil extends com.exlibris.ftp.FTPClient {

    final static Logger logger = Logger.getLogger(FTPUtil.class);

    FTPClient ftpClient = new FTPClient();

    @Override
    protected void open() throws Exception {
        JSONObject props = ConfigurationHandler.getInstance().getConfiguration();
        JSONObject ftpProps = props.getJSONObject("ftp_server");
        String server = ftpProps.getString("host");
        String user = ftpProps.getString("user");
        String pass = ftpProps.getString("password");
        String ftpPort = ftpProps.getString("ftp_port");

        // Connect to the SERVER
        ftpClient.connect(server, Integer.valueOf(ftpPort));
        if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())) {
            logger.warn("Could not connect to the server.");
            System.out.println("Could not connect to the server.");
            return;
        }

        // Login to the SERVER
        ftpClient.enterLocalPassiveMode();
        if (!ftpClient.login(user, pass)) {
            logger.warn("Could not login to the server.");
            System.out.println("Could not login to the server.");
            return;
        }
    }

    @Override
    public void close() throws Exception {
        // Disconnect from the SERVER
        ftpClient.logout();
        ftpClient.disconnect();

    }

    public void moveFile(String Sorce, String target) throws Exception {
        open();
        ftpClient.rename(Sorce, target);
        close();
    }

    @Override
    public synchronized void getFiles(String ftpFolder, String localFolder) {
        try {
            logger.info("get files from ftp folder:" + ftpFolder + " local folder : " + localFolder);

            open();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            // lists files and directories in the current working directory
            FTPFile[] files = ftpClient.listFiles(ftpFolder);

            // creating save folder
            if (files.length > 0) {
                ftpClient.makeDirectory(ftpFolder + "/OLD");
            }
            // iterates over the files and moves to OLD folder
            for (FTPFile file : files) {
                if (!isFile(file)) {
                    continue;
                }
                File localfile = new File(localFolder + "/" + file.getName());
                OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(localfile));
                // find out if a file is being used by another process?
                // only unused file can be renamed
                if (ftpClient.rename(ftpFolder + "/" + file.getName(), ftpFolder + "/OLD/" + file.getName())) {
                    if (ftpClient.retrieveFile(ftpFolder + "/OLD/" + file.getName(), outputStream)) {
                        logger.info("Success retrieving file :" + file.getName());
                    } else {
                        logger.debug("can't retrieve File move back to folder");
                        ftpClient.rename(ftpFolder + "/OLD/" + file.getName(), ftpFolder + "/" + file.getName());
                    }
                }
                outputStream.close();
            }
            // Disconnect from the SERVER
            close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isFile(FTPFile file) {
        if (!file.getName().equals(".") && !file.getName().equals("..") && !file.isDirectory()) {
            return true;
        }
        return false;
    }

    @Override
    public boolean uploadSingleFile(String localFilePath, String remoteFilePath) throws Exception {

        File localFile = new File(localFilePath);
        InputStream inputStream = new FileInputStream(localFile);
        open();
        try {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            return ftpClient.storeFile(remoteFilePath, inputStream);
        } finally {
            inputStream.close();
            close();
        }

    }

    public void removeDirectory(String parentDir, String currentDir) throws Exception {
        open();
        String dirToList = parentDir;
        if (!currentDir.equals("")) {
            dirToList += "/" + currentDir;
        }
        FTPFile[] subFiles = ftpClient.listFiles(dirToList);

        if (subFiles != null && subFiles.length > 0) {
            for (FTPFile aFile : subFiles) {
                String currentFileName = aFile.getName();
                if (currentFileName.equals(".") || currentFileName.equals("..")) {
                    // skip parent directory and the directory itself
                    continue;
                }
                String filePath = parentDir + "/" + currentDir + "/" + currentFileName;
                if (currentDir.equals("")) {
                    filePath = parentDir + "/" + currentFileName;
                }

                if (aFile.isDirectory()) {
                    // remove the sub directory
                    removeDirectory(dirToList, currentFileName);
                } else {
                    // delete the file
                    boolean deleted = ftpClient.deleteFile(filePath);
                    if (deleted) {
                        System.out.println("DELETED the file: " + filePath);
                    } else {
                        System.out.println("CANNOT delete the file: " + filePath);
                    }
                }
            }
            // finally, remove the directory itself
            boolean removed = ftpClient.removeDirectory(dirToList);
            if (removed) {
                System.out.println("REMOVED the directory: " + dirToList);
            } else {
                System.out.println("CANNOT remove the directory: " + dirToList);
            }
        }

    }

    @Override
    public void closeconn() throws Exception {
        close();
    }
}