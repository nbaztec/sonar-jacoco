/*
 * SonarQube JaCoCo Plugin
 * Copyright (C) 2018-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.jacoco;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class JacocoSensor implements Sensor {
  private static final Logger LOG = Loggers.get(JacocoSensor.class);

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.name("JaCoCo XML Report Importer");
  }

  @Override
  public void execute(SensorContext context) {
    ReportPathsProvider reportPathsProvider = new ReportPathsProvider(context);
    Iterable<InputFile> inputFiles = context.fileSystem().inputFiles(context.fileSystem().predicates().all());
    FileLocator locator = new FileLocator(inputFiles);
    ReportImporter importer = new ReportImporter(context);

    importReports(reportPathsProvider, locator, importer);
  }

  void importReports(ReportPathsProvider reportPathsProvider, FileLocator locator, ReportImporter importer) {
    Collection<Path> reportPaths = reportPathsProvider.getPaths();
    if (reportPaths.isEmpty()) {
      LOG.info("No report imported, no coverage information will be imported by JaCoCo XML Report Importer");
      return;
    }

    LOG.info("Importing {} report(s). Turn your logs in debug mode in order to see the exhaustive list.", reportPaths.size());

    for (Path reportPath : reportPaths) {
      LOG.debug("Reading report '{}'", reportPath);
      try {
        importReport(new XmlReportParser(reportPath), locator, importer);
      } catch (Exception e) {
        LOG.error("Coverage report '{}' could not be read/imported. Error: {}", reportPath, e);
      }
    }
  }

  void importReport(XmlReportParser reportParser, FileLocator locator, ReportImporter importer) {
    List<XmlReportParser.SourceFile> sourceFiles = reportParser.parse();

    boolean kotlinPackageScheme = false;
    if (sourceFiles.size() != 0) {
      XmlReportParser.SourceFile sourceFile = sourceFiles.get(0);

      String packageName = sourceFile.packageName();
      String fileName = sourceFile.name();

      InputFile inputFile = locator.getInputFile(packageName, fileName);
      if (inputFile == null) {
        inputFile = locator.getInputFile(packageName.replace(sourceFile.rootPackageName() + "/", ""), fileName);
        kotlinPackageScheme = inputFile != null;
      }
    }

    for (XmlReportParser.SourceFile sourceFile : sourceFiles) {
      String packagePath = sourceFile.packageName();
      if (kotlinPackageScheme) {
        packagePath = packagePath.replace(sourceFile.rootPackageName() + "/", "");
      }

      InputFile inputFile = locator.getInputFile(packagePath, sourceFile.name());
      if (inputFile == null) {
        continue;
      }

      try {
        importer.importCoverage(sourceFile, inputFile);
      } catch (IllegalStateException e) {
        LOG.error("Cannot import coverage information for file '{}', coverage data is invalid. Error: {}", inputFile, e);
      }
    }
  }
}
