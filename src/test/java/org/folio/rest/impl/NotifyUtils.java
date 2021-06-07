package org.folio.rest.impl;

import java.io.FileReader;
import java.io.IOException;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.folio.rest.tools.utils.ModuleName;

public class NotifyUtils {
    private NotifyUtils() {
    }

    public static String getModuleNameAndVersion() throws IOException, XmlPullParserException {
        Model model = new MavenXpp3Reader().read(new FileReader("pom.xml"));

        return ModuleName.getModuleName().replaceAll("_", "-") +
                "-" + model.getVersion();
    }
}
