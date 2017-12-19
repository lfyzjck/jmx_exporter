package io.prometheus.jmx;

import javax.management.ObjectName;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by jiachengkun on 2017/12/19.
 */
public class Scraper {
  public static interface MBeanReceiver {
    void recordBean(
            String domain,
            LinkedHashMap<String, String> beanProperties,
            LinkedList<String> attrKeys,
            String attrName,
            String attrType,
            String attrDescription,
            Object value);
  }

  protected MBeanReceiver receiver;

  protected List<ObjectName> whitelistObjectNames, blacklistObjectNames;

  public void doScrape() throws Exception {

  }
}
