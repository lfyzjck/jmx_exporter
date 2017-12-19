package io.prometheus.jmx;

import com.google.api.client.http.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ArrayMap;
import com.google.api.client.util.Key;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularType;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.naming.Context;
import javax.rmi.ssl.SslRMIClientSocketFactory;


public class JmxJsonScraper extends Scraper{
  private static final Logger logger = Logger.getLogger(JmxScraper.class.getName());;
  private static final Pattern PROPERTY_PATTERN = Pattern.compile(
          "([^,=:\\*\\?]+)" + // Name - non-empty, anything but comma, equals, colon, star, or question mark
                  "=" +  // Equals
                  "(" + // Either
                  "\"" + // Quoted
                  "(?:" + // A possibly empty sequence of
                  "[^\\\\\"]" + // Anything but backslash or quote
                  "|\\\\\\\\" + // or an escaped backslash
                  "|\\\\n" + // or an escaped newline
                  "|\\\\\"" + // or an escaped quote
                  "|\\\\\\?" + // or an escaped question mark
                  "|\\\\\\*" + // or an escaped star
                  ")*" +
                  "\"" +
                  "|" + // Or
                  "[^,=:\"]*" + // Unquoted - can be empty, anything but comma, equals, colon, or quote
                  ")");

  public static class MBeanInfoList {
    @Key("beans")
    private List<Map<String, Object>> beans;

    public List<Map<String, Object>> getBeans() {
      return beans;
    }
  }

  private String jmxHttpUrl;
  static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  static final JsonFactory JSON_FACTORY = new JacksonFactory();

  public JmxJsonScraper(String jmxHttpUrl, List<ObjectName> whitelistObjectNames, List<ObjectName> blacklistObjectNames, MBeanReceiver receiver) {
    this.jmxHttpUrl = jmxHttpUrl;
    this.receiver = receiver;
    this.whitelistObjectNames = whitelistObjectNames;
    this.blacklistObjectNames = blacklistObjectNames;
  }

  /**
   * Get a list of mbeans on host_port and scrape their values.
   *
   * Values are passed to the receiver in a single thread.
   */
  @Override
  public void doScrape() throws Exception {
    JMXConnector jmxc = null;
    HttpRequestFactory requestFactory =
            HTTP_TRANSPORT.createRequestFactory(new HttpRequestInitializer() {
              @Override
              public void initialize(HttpRequest request) {
                request.setParser(new JsonObjectParser(JSON_FACTORY));
              }
            });
    GenericUrl url = new GenericUrl(jmxHttpUrl);
    HttpRequest request = requestFactory.buildGetRequest(url);
    parseResponse(request.execute());
    /*
    try {
      // Query MBean names, see #89 for reasons queryMBeans() is used instead of queryNames()
      Set<ObjectInstance> mBeanNames = new HashSet();
      for (ObjectName name : whitelistObjectNames) {
        mBeanNames.addAll(beanConn.queryMBeans(name, null));
      }
      for (ObjectName name : blacklistObjectNames) {
        mBeanNames.removeAll(beanConn.queryMBeans(name, null));
      }
      for (ObjectInstance name : mBeanNames) {
        long start = System.nanoTime();
        scrapeBean(beanConn, name.getObjectName());
        logger.fine("TIME: " + (System.nanoTime() - start) + " ns for " + name.getObjectName().toString());
      }
    } finally {
      if (jmxc != null) {
        jmxc.close();
      }
    }
    */
  }

  private void parseResponse(HttpResponse response) throws IOException{
    // System.out.println(response.parseAsString());
    MBeanInfoList info = response.parseAs(MBeanInfoList.class);
    info.getBeans().forEach(item -> {
      parseBean(item);
    });
  }

  private void parseBean(Map<String, Object> mBeanInfo) {
    String mbeanName = (String)mBeanInfo.get("name");
    String modelerType = (String)mBeanInfo.get("modelerType");
    ObjectName objName;
    try {
      objName = new ObjectName(mbeanName);
    } catch (JMException e) {
      logScrape(mbeanName, "getMbeanInfo Fail: " + e);
      return;
    }
    List<String> reversedAttrs = Arrays.asList("name", "modelerType", "ObjectName");
    mBeanInfo.forEach((attrName, value) -> {
      if(!reversedAttrs.contains(attrName)) {
        logScrape(objName, attrName, "process");
        processBeanValue(
                objName.getDomain(),
                getKeyPropertyList(objName),
                new LinkedList<String>(),
                attrName,
                value.getClass().toString(),
                attrName,
                value
        );
      }
    });
  }

  static LinkedHashMap<String, String> getKeyPropertyList(ObjectName mbeanName) {
    // Implement a version of ObjectName.getKeyPropertyList that returns the
    // properties in the ordered they were added (the ObjectName stores them
    // in the order they were added).
    LinkedHashMap<String, String> output = new LinkedHashMap<String, String>();
    String properties = mbeanName.getKeyPropertyListString();
    Matcher match = PROPERTY_PATTERN.matcher(properties);
    while (match.lookingAt()) {
      output.put(match.group(1), match.group(2));
      properties = properties.substring(match.end());
      if (properties.startsWith(",")) {
        properties = properties.substring(1);
      }
      match.reset(properties);
    }
    return output;
  }

  /**
   * Check if val is a basic type object
   * @param val
   * @return
   */
  private boolean isBasicType(Object val) {
    return val instanceof Number || val instanceof String || val instanceof Boolean;
  }

  /**
   * Recursive function for exporting the values of an mBean.
   * JMX is a very open technology, without any prescribed way of declaring mBeans
   * so this function tries to do a best-effort pass of getting the values/names
   * out in a way it can be processed elsewhere easily.
   */
  private void processBeanValue(
          String domain,
          LinkedHashMap<String, String> beanProperties,
          LinkedList<String> attrKeys,
          String attrName,
          String attrType,
          String attrDescription,
          Object value) {
    if (value == null) {
      logScrape(domain + beanProperties + attrName, "null");
    } else if (isBasicType(value)) {
      logScrape(domain + beanProperties + attrName, value.toString());
      this.receiver.recordBean(
              domain,
              beanProperties,
              attrKeys,
              attrName,
              attrType,
              attrDescription,
              value);
    } else if (value instanceof ArrayMap) {
      logScrape(domain + beanProperties + attrName, "ArrayMap");
      ArrayMap<String, Object> composite = (ArrayMap<String, Object>) value;
      attrKeys = new LinkedList<String>(attrKeys);
      attrKeys.add(attrName);
      for(String key: composite.keySet()) {
        Object valu = composite.get(key);
        String typ = value.getClass().getTypeName();
        processBeanValue(
                domain,
                beanProperties,
                attrKeys,
                key,
                typ,
                key,
                valu);
      };
    } else if (value instanceof ArrayList) {
      // jackson deserialize json list as ArrayList but not Array
      ArrayList valu = (ArrayList) value;
      LinkedHashMap<String, String> l2s = new LinkedHashMap<String, String>(beanProperties);
      for(Object item: valu) {
        if (item instanceof ArrayMap) {
          ArrayMap val = (ArrayMap) item;
          if(val.containsKey("key") && val.containsKey("value")){
            String subKey = (String) val.get("key");
            Object subVal = val.get("value");
            l2s.put("key", subKey);
            processBeanValue(
                    domain,
                    l2s,
                    attrKeys,
                    attrName,
                    val.getClass().getTypeName(),
                    attrName,
                    subVal);
          }
        } else {
          logScrape(domain, "arrays are unsupported");
          break;
        }
      }
    } else {
      logScrape(domain + beanProperties, attrType + " is not exported");
    }
  }

  /**
   * For debugging.
   */
  private static void logScrape(ObjectName mbeanName, String attrName, String msg) {
    logScrape(mbeanName + "'_'" + attrName, msg);
  }
  private static void logScrape(String name, String msg) {
    logger.log(Level.FINE, "scrape: '" + name + "': " + msg);
  }

  private static class StdoutWriter implements MBeanReceiver {
    public void recordBean(
            String domain,
            LinkedHashMap<String, String> beanProperties,
            LinkedList<String> attrKeys,
            String attrName,
            String attrType,
            String attrDescription,
            Object value) {
      System.out.println(domain +
              beanProperties +
              attrKeys +
              attrName +
              ": " + value);
    }
  }

  /**
   * Convenience function to run standalone.
   */
  public static void main(String[] args) throws Exception {
    List<ObjectName> objectNames = new LinkedList<ObjectName>();
    objectNames.add(null);
    if (args.length >= 3){
      new JmxJsonScraper(args[0], objectNames, new LinkedList<ObjectName>(), new StdoutWriter()).doScrape();
    }
    else if (args.length > 0){
      new JmxJsonScraper(args[0], objectNames, new LinkedList<ObjectName>(), new StdoutWriter()).doScrape();
    }
    else {
      new JmxJsonScraper("http://localhost:10002/jmx", objectNames, new LinkedList<ObjectName>(), new StdoutWriter()).doScrape();
    }
  }
}

