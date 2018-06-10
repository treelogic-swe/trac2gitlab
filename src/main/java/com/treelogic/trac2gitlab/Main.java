package com.treelogic.trac2gitlab;

import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabIssue;
import org.gitlab.api.models.GitlabProject;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static String GITLAB_URL = "https://example.com:8443";
    public static String API_TOKEN = "KIZv9dtyGg6bafQ3S7rt";
    public static String PROJECT_NAME = "alltasksproject";
    public static boolean SSL_VALIDATION_DISABLED = false;
    public static final String DOMAIN = "example.com";
    public static final String PATH_TO_TRAC_DB_FILE = "/path_to_trac_folder/trac.db";

    public static void main(String[] ss) throws SQLException, IOException, KeyManagementException, NoSuchAlgorithmException {
        if (SSL_VALIDATION_DISABLED) {
            disableSSLValidation();
        }
        GitlabAPI gitlabAPI = GitlabAPI.connect(GITLAB_URL, API_TOKEN);
        GitlabProject gitlabProject1 = gitlabAPI.createProject(PROJECT_NAME);
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + PATH_TO_TRAC_DB_FILE);
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);
        }
        Statement st = c.createStatement();
        String query = "SELECT id, version, resolution, summary, description from ticket";
        ResultSet rs = null;
        try {
            rs = st.executeQuery(query);
            while (rs.next()) {
                int id = rs.getInt(1);
                String version = rs.getString(2); // label? milestone?
                String resolution = rs.getString(3);
                String summary = rs.getString(4);
                String description = rs.getString(5);
                GitlabIssue gitlabIssue = gitlabAPI.createIssue(
                        gitlabProject1.getId(), 0, 0, version, description, summary);
                gitlabIssue.setId(gitlabIssue.getIid());// workaround, otherwise dosn'e work
                for (String comment : loadComments(c, id)) {
                    gitlabAPI.createNote(gitlabIssue, comment);
                }
                if (shouldWeCloseTheTicket(resolution)) {
                    gitlabAPI.editIssue(
                            gitlabProject1.getId(),
                            gitlabIssue.getId(),
                            0,
                            0,
                            version,
                            description,
                            summary,
                            GitlabIssue.Action.CLOSE);
                }

            }
        } finally {
            if (rs != null) {
                rs.close();
            }
        }
        System.out.println("Opened database successfully");
    }

    private static void disableSSLValidation() throws NoSuchAlgorithmException, KeyManagementException {
        //for localhost testing only
        HttpsURLConnection.setDefaultHostnameVerifier(
                new HostnameVerifier() {

                    public boolean verify(String hostname,
                                          SSLSession sslSession) {
                        if (hostname.equals(DOMAIN)) {
                            return true;
                        }
                        return false;
                    }
                });
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
    }

    private static boolean shouldWeCloseTheTicket(String resolution) {
// done         - closed
// fixed        - opened
// (null)       - opened
// duplicate    - closed
// wontfix      - closed
//              - opened
// notnecessary - opened
// invalid      - closed
        if (resolution == null) {
            return false;
        }
        return "done".endsWith(resolution) ||
                "duplicate".endsWith(resolution) ||
                "wontfix".endsWith(resolution) ||
                "invalid".endsWith(resolution);
    }

    static List<String> loadComments(Connection c, int ticketId) throws SQLException {
        Statement st = c.createStatement();
        List<String> comments = new ArrayList<>();
        String query = "select ticket, " +
                "CAST(oldvalue as REAL) as aaa, " +
                "newvalue, " +
                "author " +
                "from ticket_change " +
                "where field = \"comment\" and ticket=" + ticketId +
                " order by aaa";
        ResultSet rs = null;
        try {
            rs = st.executeQuery(query);
            while (rs.next()) {
                String comment = rs.getString(3);
                String author = rs.getString(4);
                if (comment != null && comment.length() > 0) {
                    comments.add("[" + author + "]: " + comment);
                }

            }
        } finally {
            rs.close();
            st.close();
        }

        return comments;
    }
}
