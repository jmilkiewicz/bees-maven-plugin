package com.staxnet.mojo.tomcat;

//Based on org.codehaus.mojo.tomcat.RunMojo (Copyright 2006 Mark Hobson), which was licensed
//under the Apache License, Version 2.0. You may obtain a copy of the License at
//       http://www.apache.org/licenses/LICENSE-2.0 

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.cloudbees.api.ApplicationDeployArchiveResponse;
import com.cloudbees.api.BeesClient;
import com.cloudbees.api.BeesClientConfiguration;
import com.cloudbees.api.HashWriteProgress;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import com.staxnet.appserver.config.AppConfig;
import com.staxnet.appserver.config.AppConfigHelper;
import com.staxnet.appserver.utils.ZipHelper;
import com.staxnet.appserver.utils.ZipHelper.ZipEntryHandler;

/**
 * Deploys the current project package to the Stax service.
 * 
 * @goal deploy
 * @execute phase = "package"
 * @requiresDependencyResolution runtime
 * 
 */
public class DeployMojo extends AbstractI18NMojo
{
    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------
    /**
     * The packaging of the Maven project that this goal operates upon.
     * 
     * @parameter expression = "${project.packaging}"
     * @required
     * @readonly
     */
    private String packaging;

    /**
     * The id of the stax application.
     * 
     * @parameter expression="${bees.appid}"
     */
    private String appid;

    /**
     * Bees api key.
     * 
     * @parameter expression="${bees.key}"
     */
    private String apikey;

    /**
     * Bees api secret.
     * 
     * @parameter expression="${bees.secret}"
     */
    private String secret;

    /**
     * Configuration environments to use.
     * 
     * @parameter expression="${bees.environment}"
     */
    private String environment;

    /**
     * Message associated with the deployment.
     * 
     * @parameter expression="${bees.message}"
     */
    private String message;

    /**
     * Bees deployment server.
     *
     * @parameter expression="${bees.apiurl}" default-value = "https://api.cloudbees.com/api"
     * @required
     */
    private String apiurl;

    /**
     * The web resources directory for the web application being run.
     * 
     * @parameter expression="${basedir}/src/main/webapp"
     */
    private String warSourceDirectory;

    /**
     * The path to the Bees deployment descriptor.
     * 
     * @parameter expression="${bees.appxml}" default-value =
     *            "${basedir}/src/main/config/stax-application.xml"
     */
    private File appConfig;

    /**
     * The path to the J2EE appplication deployment descriptor.
     * 
     * @parameter expression="${bees.j2ee.appxml}" default-value = "${basedir}/src/main/config/application.xml"
     */
    private File appxml;

    /**
     * The set of dependencies for the web application being run.
     * 
     * @parameter default-value = "${basedir}"
     * @required
     * @readonly
     */
    private String baseDir;

    /**
     * The package output file.
     * 
     * @parameter default-value =
     *            "${project.build.directory}/${project.build.finalName}.${project.packaging}"
     * @required
     * @readonly
     */
    private File warFile;

    /**
     * The packaged deployment file.
     * 
     * @parameter default-value = "${project.build.directory}/stax-deploy.zip"
     * @required
     * @readonly
     */
    private File deployFile;

    /**
     * Bees deployment type.
     *
     * @parameter expression="${bees.delta}" default-value = "true"
     */
    private String delta;

    /**
     * Bees http proxyHost.
     * @parameter expression="${bees.proxyHost}"
     */
    private String proxyHost;

    /**
     * Bees http proxyPort.
     * @parameter expression="${bees.proxyPort}"
     */
    private String proxyPort;

    /**
     * Bees http proxyUser.
     * @parameter expression="${bees.proxyUser}"
     */
    private String proxyUser;

    /**
     * Bees http proxyPassword.
     * @parameter expression="${bees.proxyPassword}"
     */
    private String proxyPassword;

    /**
     * Bees container type.
     * @parameter expression="${bees.containerType}"
     */
    private String containerType;

    /**
     * deploy parameters
     * @parameter
     */
    private Properties parameters;
    
    /**
     * Gets whether this project uses WAR packaging.
     * 
     * @return whether this project uses WAR packaging
     */
    protected boolean isWar()
    {
        return "war".equals(packaging);
    }

    /*
     * @see org.apache.maven.plugin.AbstractMojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        // Read SDK config file
        Properties properties = getConfigProperties();
        // Initialize the parameter values (to allow system property overrides
        // from the command line)
        initParameters(properties);

        // ensure project is a web application
        if (!isWar()) {
            getLog().info(getMessage("RunMojo.nonWar"));
            return;
        }
        
        // create the deployment package
        if(appConfig.exists() && appxml.exists())
        {
            FileOutputStream fstream = null;
            try {
                fstream = new FileOutputStream(deployFile);
                ZipOutputStream zos = new ZipOutputStream(fstream);
                ZipHelper.addFileToZip(warFile, "webapp.war", zos);
                ZipHelper.addFileToZip(appConfig,
                                       "META-INF/stax-application.xml", zos);
                ZipHelper.addFileToZip(appxml, "META-INF/application.xml", zos);
                zos.close();
            } catch (Exception e) {
                throw new MojoFailureException(
                                               this,
                                               getMessage("StaxMojo.packageFailed"),
                                               e.getMessage());
            }
        }
        else
        {
            deployFile = warFile;
        }

        // deploy the application to the server
        try {
            initCredentials();
            
            AppConfig appConfig =
                getAppConfig(
                    deployFile,
                             ApplicationHelper.getEnvironmentList(environment),
                             new String[] { "deploy" });
            initAppId(appConfig);

            String defaultAppDomain = properties.getProperty("bees.project.app.domain");
            String[] appIdParts = appid.split("/");
            String domain = null;
            if (appIdParts.length > 1) {
                domain = appIdParts[0];
            } else if (defaultAppDomain != null &&
                       !defaultAppDomain.equals(""))
            {
                domain = defaultAppDomain;
                appid = domain + "/" + appid;
            } else {
                throw new MojoExecutionException(
                                         "default app domain could not be determined, appid needs to be fully-qualified ");
            }
            
            environment = StringHelper.join(appConfig.getAppliedEnvironments()
                                            .toArray(new String[0]), ",");
            
            System.out.println(String.format(
                                             "Deploying application %s (environment: %s)",
                                             appid, environment));

            BeesClientConfiguration beesClientConfiguration = new BeesClientConfiguration(apiurl, apikey, secret, "xml", "1.0");

            // Set proxy information
            beesClientConfiguration.setProxyHost(properties.getProperty("bees.api.proxy.host", proxyHost));
            if (properties.getProperty("bees.api.proxy.port") != null || proxyPort != null)
                beesClientConfiguration.setProxyPort(Integer.parseInt(properties.getProperty("bees.api.proxy.port", proxyPort)));
            beesClientConfiguration.setProxyUser(properties.getProperty("bees.api.proxy.user", proxyUser));
            beesClientConfiguration.setProxyPassword(properties.getProperty("bees.api.proxy.password", proxyPassword));

            BeesClient client = new BeesClient(beesClientConfiguration);
            String str = properties.getProperty("bees.api.verbose", "false");
            client.setVerbose(Boolean.parseBoolean(str));

            String archiveType = deployFile.getName().endsWith(".war") ? "war" : "ear";

            boolean deployDelta = (delta == null || delta.equalsIgnoreCase("true")) ? true : false;

            Map<String, String> parameters = new HashMap<String, String>();
            if (containerType != null)
                parameters.put("containerType", containerType);

            
            com.cloudbees.api.ApplicationDeployArgs.Builder argBuilder = (new com.cloudbees.api.ApplicationDeployArgs.Builder(
                    appid)).environment(environment).description(message)
                    .deployPackage(deployFile, archiveType)
                    .withParams(parameters).withVars(getConfigVariables())
                    .withProgressFeedback(new HashWriteProgress());
            if (deployDelta) {
                argBuilder = argBuilder.withIncrementalDeployment();
            }
            ApplicationDeployArchiveResponse res = client.applicationDeployArchive(argBuilder.build());
            System.out.println("Application " + res.getId() + " deployed: " + res.getUrl());

        } catch (Exception e) {
            e.printStackTrace();
            throw new MojoFailureException(
                                           this,
                                           getMessage("StaxMojo.deployFailed"),
                                           e.getMessage());
        }
    }

    private Map<String, String> getConfigVariables() {
        Map<String, String> result = Collections.emptyMap();
        if(parameters != null){
            result =  new HashMap(parameters);
        }
        return result;
    }

    private void initAppId(AppConfig appConfig) throws IOException
    {
        if (appid == null || appid.equals("")) {
            appid = appConfig.getApplicationId();

            if (appid == null || appid.equals(""))
                appid = promptForAppId();
            
            if (appid == null || appid.equals(""))
                throw new IllegalArgumentException(
                                                   "No application id specified");
        }
    }

    private String getSysProperty(String parameterName, String defaultValue)
    {
        String value = System.getProperty(parameterName);
        if (value != null)
            return value;
        else
            return defaultValue;
    }

    /**
     * Initialize the parameter values (to allow system property overrides)
     */
    private void initParameters(Properties properties)
    {
        appid = getSysProperty("bees.appid", appid);

        apikey = getSysProperty("bees.apikey", apikey);
        if (apikey == null)
            apikey = properties.getProperty("bees.api.key");

        secret = getSysProperty("bees.secret", secret);
        if (secret == null)
            secret = properties.getProperty("bees.api.secret");

        apiurl = getSysProperty("bees.api.url", apiurl);
        if (apiurl == null)
        	apiurl = properties.getProperty("bees.api.url");
        environment = getSysProperty("bees.environment", environment);
        message = getSysProperty("bees.message", message);
        delta = getSysProperty("bees.delta", delta);
        proxyHost = getSysProperty("bees.proxyHost", proxyHost);
        proxyPort = getSysProperty("bees.proxyPort", proxyPort);
        proxyUser = getSysProperty("bees.proxyUser", proxyUser);
        proxyPassword = getSysProperty("bees.proxyPassword", proxyPassword);
        containerType = getSysProperty("bees.containerType", containerType);
    }

    private Properties getConfigProperties()
    {
        Properties properties = new Properties();
        File userConfigFile = new File(System.getProperty("user.home"), ".bees/bees.config");
        if (userConfigFile.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(userConfigFile);
                properties.load(fis);
                fis.close();
            } catch (IOException e) {
                getLog().error(e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ignored) {}
                }
            }
        }
        return properties;
    }

    private void initCredentials() throws IOException
    {
        boolean promptForApiKey = apikey == null;
        boolean promptForApiSecret = apikey == null || secret == null;
        BufferedReader inputReader =
            new BufferedReader(new InputStreamReader(System.in));
        if (promptForApiKey) {
            System.out.print("Enter your CloudBees API key: ");
            apikey = inputReader.readLine();
        }

        if (promptForApiSecret) {
            System.out.print("Enter your CloudBees Api secret: ");
            secret = inputReader.readLine();
        }
    }
    
    private String promptForAppId() throws IOException
    {
        BufferedReader inputReader =
            new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Enter application ID (ex: account/appname): ");
        String appId = inputReader.readLine();
        return appId;
    }

    private AppConfig getAppConfig(File deployZip,
                                   final String[] environments,
                                   final String[] implicitEnvironments) throws IOException
    {
        final AppConfig appConfig = new AppConfig();

        FileInputStream fin = new FileInputStream(deployZip);
        try {
            ZipHelper.unzipFile(fin, new ZipEntryHandler()
            {
                public void unzip(ZipEntry entry, InputStream zis) throws IOException
                {
                    if (entry.getName().equals(
                                               "META-INF/stax-application.xml") ||
                        entry.getName().equals("WEB-INF/stax-web.xml") ||
                        entry.getName().equals("WEB-INF/cloudbees-web.xml"))
                    {
                        AppConfigHelper.load(appConfig, zis, null,
                                             environments,
                                             implicitEnvironments);
                    }
                }
            }, false);
        } finally {
            fin.close();
        }

        return appConfig;
    }

}
