package com.vincenthzr.locationspoofer.ui.route;

import java.io.File;
import java.text.NumberFormat;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Android formats getString(id, args) with the resource locale and Java Formatter. */
public class RouteSpeedLabelsTest {
    @Test public void currentSpeedRetainsTenthsInAllTranslations() throws Exception {
        check("values", Locale.ENGLISH);
        check("values-zh", Locale.SIMPLIFIED_CHINESE);
        check("values-ar", Locale.forLanguageTag("ar"));
    }

    private static void check(String dir, Locale locale) throws Exception {
        File file = new File("src/main/res/" + dir + "/strings.xml");
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        String format = XPathFactory.newInstance().newXPath().evaluate(
                "/resources/string[@name='route_current_speed_format']", doc);
        NumberFormat number = NumberFormat.getNumberInstance(locale);
        number.setMinimumFractionDigits(1);
        number.setMaximumFractionDigits(1);
        for (double speed : new double[]{1.4, 5.5, 2.7, 0.0}) {
            String rendered = String.format(locale, format, speed);
            assertTrue(dir + ": " + rendered, rendered.contains(number.format(speed)));
        }
    }
}
