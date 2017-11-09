package com.ibm.ws.security.javaeesec.fat;

import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.log.Log;
import com.ibm.ws.apacheds.EmbeddedApacheDS;
import com.ibm.ws.security.javaeesec.fat_helper.WCApplicationHelper;
import com.ibm.ws.security.javaeesec.fat_singleIS.HttpAuthenticationMechanismSingleISTest;

import componenttest.annotation.MinimumJavaLevel;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.custom.junit.runner.Mode;
import componenttest.custom.junit.runner.Mode.TestMode;
import componenttest.custom.junit.runner.OnlyRunInJava7Rule;

/*
 * IBM Confidential
 *
 * OCO Source Materials
 *
 * Copyright IBM Corp. 2017
 *
 * The source code for this program is not published or other-
 * wise divested of its trade secrets, irrespective of what has
 * been deposited with the U.S. Copyright Office.
 */
/**
 * Test Description:
 */
@MinimumJavaLevel(javaLevel = 1.7, runSyntheticTest = false)
@RunWith(FATRunner.class)
@Mode(TestMode.FULL)
public class HttpAuthenticationMechanismTest extends HttpAuthenticationMechanismSingleISTest {

    private static EmbeddedApacheDS ldapServer = null;

    @Rule
    public TestName name = new TestName();

    @BeforeClass
    public static void setUp() throws Exception {
        // if (!OnlyRunInJava7Rule.IS_JAVA_7_OR_HIGHER)
        // return; // skip the test setup

        setupldapServer();

//        LDAPUtils.addLDAPVariables(myServer);
//        myServer.installUserBundle("security.jaspi.user.feature.test_1.0");
//        myServer.installUserFeature("jaspicUserTestFeature-1.0");
        WCApplicationHelper.addWarToServerApps(myServer, "JavaEESecBasicAuthServlet.war", true, JAR_NAME, false, "web.jar.base", "web.war.basic");
        WCApplicationHelper.addWarToServerApps(myServer, "JavaEESecAnnotatedBasicAuthServlet.war", true, JAR_NAME, false, "web.jar.base", "web.war.annotatedbasic");
        WCApplicationHelper.addWarToServerApps(myServer, "JavaEEsecFormAuth.war", true, JAR_NAME, false, "web.jar.base", "web.war.formlogin");
        WCApplicationHelper.addWarToServerApps(myServer, "JavaEEsecFormAuthRedirect.war", true, JAR_NAME, false, "web.jar.base", "web.war.redirectformlogin");
        myServer.copyFileToLibertyInstallRoot("lib/features", "internalFeatures/javaeesecinternals-1.0.mf");

        myServer.startServer(true);
//        myServer.addInstalledAppForValidation(DEFAULT_APP);
//        verifyServerStartedWithJaspiFeature(myServer);
        urlBase = "http://" + myServer.getHostname() + ":" + myServer.getHttpDefaultPort();

    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (!OnlyRunInJava7Rule.IS_JAVA_7_OR_HIGHER)
            return; // skip the test teardown
        myServer.stopServer();
//        myServer.uninstallUserBundle("security.jaspi.user.feature.test_1.0");
//        myServer.uninstallUserFeature("jaspicUserTestFeature-1.0");

        if (ldapServer != null) {
            try {
                ldapServer.stopService();
            } catch (Exception e) {
                Log.error(logClass, "teardown", e, "LDAP server threw error while stopping. " + e.getMessage());
            }
        }

    }

    @Before
    public void setupConnection() {
        httpclient = new DefaultHttpClient();
    }

    @After
    public void cleanupConnection() {
        httpclient.getConnectionManager().shutdown();
    }

    @Override
    protected String getCurrentTestName() {
        return name.getMethodName();
    }

    private static void setupldapServer() throws Exception {
        ldapServer = new EmbeddedApacheDS("HTTPAuthLDAP");
        ldapServer.addPartition("test", "o=ibm,c=us");
        ldapServer.startServer(Integer.parseInt(System.getProperty("ldap.1.port")));

        Entry entry = ldapServer.newEntry("o=ibm,c=us");
        entry.add("objectclass", "organization");
        entry.add("o", "ibm");
        ldapServer.add(entry);

        entry = ldapServer.newEntry("uid=jaspildapuser1,o=ibm,c=us");
        entry.add("objectclass", "inetorgperson");
        entry.add("uid", "jaspildapuser1");
        entry.add("sn", "jaspildapuser1sn");
        entry.add("cn", "jaspiuser1");
        entry.add("userPassword", "s3cur1ty");
        ldapServer.add(entry);

    }
}
