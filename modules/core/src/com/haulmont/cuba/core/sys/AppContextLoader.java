/*
 * Copyright (c) 2009 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Konstantin Krivopustov
 * Created: 23.12.2009 15:02:34
 *
 * $Id$
 */
package com.haulmont.cuba.core.sys;

import com.haulmont.chile.core.datatypes.Datatypes;
import com.haulmont.chile.core.datatypes.FormatStrings;
import com.haulmont.chile.core.model.utils.MetadataUtils;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.persistence.PersistenceConfigProcessor;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrLookup;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.commons.lang.text.StrTokenizer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

public class AppContextLoader implements ServletContextListener {

    public static final String SPRING_CONTEXT_CONFIG = "cuba.springContextConfig";
    public static final String PERSISTENCE_CONFIG = "cuba.persistenceConfig";

    public static final String APP_PROPS_CONFIG_PARAM = "appPropertiesConfig";

    public static final String APP_PROPS_PARAM = "appProperties";

    private static Log log = LogFactory.getLog(AppContextLoader.class);

    public void contextInitialized(ServletContextEvent servletContextEvent) {
        try {
            ServletContext sc = servletContextEvent.getServletContext();

            initAppProperties(sc);

            File file = new File(AppContext.getProperty("cuba.confDir"));
            if (!file.exists())
                file.mkdirs();
            file = new File(AppContext.getProperty("cuba.tempDir"));
            if (!file.exists())
                file.mkdirs();

            initPersistenceConfig();
            initAppContext();
            initLocalization();
            initMetadata();
            initDatabase();

            AppContext.startContext();
            log.info("AppContext initialized");
        } catch (Throwable e) {
            log.error("Error initializing application", e);
            throw new RuntimeException(e);
        }
    }

    protected void initLocalization() {
        String mp = AppContext.getProperty("cuba.core.messagesPack");
        MessageUtils.setMessagePack(mp);

        for (Locale locale : ConfigProvider.getConfig(GlobalConfig.class).getAvailableLocales().values()) {
            Datatypes.setFormatStrings(
                    locale,
                    new FormatStrings(
                            MessageProvider.getMessage(mp, "numberDecimalSeparator", locale).charAt(0),
                            MessageProvider.getMessage(mp, "numberGroupingSeparator", locale).charAt(0),
                            MessageProvider.getMessage(mp, "integerFormat", locale),
                            MessageProvider.getMessage(mp, "doubleFormat", locale),
                            MessageProvider.getMessage(mp, "dateFormat", locale),
                            MessageProvider.getMessage(mp, "dateTimeFormat", locale),
                            MessageProvider.getMessage(mp, "timeFormat", locale),
                            MessageProvider.getMessage(mp, "trueString", locale),
                            MessageProvider.getMessage(mp, "falseString", locale)
                    )
            );
        }
    }

    protected void initMetadata() {
        MetadataUtils.setSerializationSupportSession(MetadataProvider.getSession());
    }

    protected void initDatabase() {
        if (!Boolean.valueOf(AppContext.getProperty("cuba.automaticDatabaseUpdate")))
            return;

        DbUpdater updater = (DbUpdater) AppContext.getApplicationContext().getBean(DbUpdater.NAME);
        updater.updateDatabase();
    }

    protected void initAppProperties(ServletContext sc) {
        // get properties from web.xml
        String appProperties = sc.getInitParameter(APP_PROPS_PARAM);
        if (appProperties != null) {
            StrTokenizer tokenizer = new StrTokenizer(appProperties);
            for (String str : tokenizer.getTokenArray()) {
                int i = str.indexOf("=");
                if (i < 0)
                    continue;
                String name = StringUtils.substring(str, 0, i);
                String value = StringUtils.substring(str, i+1);
                if (!StringUtils.isBlank(name)) {
                    AppContext.setProperty(name, value);
                }
            }
        }

        // get properties from core-app.properties
        String propsConfigName = sc.getInitParameter(APP_PROPS_CONFIG_PARAM);
        if (propsConfigName == null)
            throw new IllegalStateException(APP_PROPS_CONFIG_PARAM + " servlet context parameter not defined");

        final Properties properties = new Properties();

        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        StrTokenizer tokenizer = new StrTokenizer(propsConfigName);
        for (String str : tokenizer.getTokenArray()) {
            Resource resource = resourceLoader.getResource(str);
            if (resource.exists()) {
                InputStream stream = null;
                try {
                    stream = resource.getInputStream();
                    properties.load(stream);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    IOUtils.closeQuietly(stream);
                }
            } else {
                log.warn("Resource " + str + " not found, ignore it");
            }
        }

        StrSubstitutor substitutor = new StrSubstitutor(new StrLookup() {
            @Override
            public String lookup(String key) {
                String subst = properties.getProperty(key);
                return subst != null ? subst : System.getProperty(key);
            }
        });
        for (Object key : properties.keySet()) {
            String value = substitutor.replace(properties.getProperty((String) key));
            AppContext.setProperty((String) key, value);
        }
    }

    protected void initPersistenceConfig() {
        String configProperty = AppContext.getProperty(PERSISTENCE_CONFIG);
        if (StringUtils.isBlank(configProperty)) {
            throw new IllegalStateException("Missing " + PERSISTENCE_CONFIG + " application property");
        }

        StrTokenizer tokenizer = new StrTokenizer(configProperty);
        PersistenceConfigProcessor processor = new PersistenceConfigProcessor();
        processor.setSourceFiles(tokenizer.getTokenList());

        String dataDir = AppContext.getProperty("cuba.dataDir");
        processor.setOutputFile(dataDir + "/persistence.xml");

        processor.create();
    }

    protected void initAppContext() {
        String configProperty = AppContext.getProperty(SPRING_CONTEXT_CONFIG);
        if (StringUtils.isBlank(configProperty)) {
            throw new IllegalStateException("Missing " + SPRING_CONTEXT_CONFIG + " application property");
        }

        StrTokenizer tokenizer = new StrTokenizer(configProperty);
        String[] locations = tokenizer.getTokenArray();

        ApplicationContext appContext = new ClassPathXmlApplicationContext(locations);
        AppContext.setApplicationContext(appContext);
    }

    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        AppContext.stopContext();
        AppContext.setApplicationContext(null);
    }
}
