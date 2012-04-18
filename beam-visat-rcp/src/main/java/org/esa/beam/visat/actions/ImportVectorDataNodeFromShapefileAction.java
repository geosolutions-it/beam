/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.visat.actions;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.swing.TableLayout;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import org.esa.beam.framework.datamodel.VectorDataNode;
import org.esa.beam.framework.ui.ModalDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.framework.ui.command.ExecCommand;
import org.esa.beam.framework.ui.crs.CrsSelectionPanel;
import org.esa.beam.framework.ui.crs.CustomCrsForm;
import org.esa.beam.framework.ui.crs.PredefinedCrsForm;
import org.esa.beam.framework.ui.crs.ProductCrsForm;
import org.esa.beam.util.FeatureUtils;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.io.BeamFileFilter;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.toolviews.layermanager.layersrc.shapefile.SLDUtils;
import org.geotools.feature.FeatureCollection;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.styling.Style;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public class ImportVectorDataNodeFromShapefileAction extends ExecCommand {

    private VectorDataNodeImporter importer;

    @Override
    public void actionPerformed(final CommandEvent event) {
        final BeamFileFilter filter = new BeamFileFilter("SHAPEFILE",
                                                         new String[]{".shp"},
                                                         "ESRI Shapefiles");
        importer = new VectorDataNodeImporter(getHelpId(), filter, new VdnShapefileReader(), "Import Shapefile", "shape.io.dir");
        importer.importGeometry(VisatApp.getApp());
        VisatApp.getApp().updateState();
    }


    @Override
    public void updateState(final CommandEvent event) {
        final Product product = VisatApp.getApp().getSelectedProduct();
        setEnabled(product != null);
    }

    class VdnShapefileReader implements ImportGeometryAction.VectorDataNodeReader {

        @Override
        public VectorDataNode readVectorDataNode(VisatApp visatApp, File file, Product product, String helpId, ProgressMonitor pm) throws IOException {

            MyFeatureCrsProvider crsProvider = new MyFeatureCrsProvider(visatApp, helpId);
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = FeatureUtils.loadShapefileForProduct(file,
                                                                                                                         product,
                                                                                                                         crsProvider, pm);
            Style[] styles = SLDUtils.loadSLD(file);
            ProductNodeGroup<VectorDataNode> vectorDataGroup = product.getVectorDataGroup();
            String name = VectorDataNodeImporter.findUniqueVectorDataNodeName(featureCollection.getSchema().getName().getLocalPart(),
                                                                              vectorDataGroup);
            if (styles.length > 0) {
                SimpleFeatureType featureType = SLDUtils.createStyledFeatureType(featureCollection.getSchema());


                VectorDataNode vectorDataNode = new VectorDataNode(name, featureType);
                FeatureCollection<SimpleFeatureType, SimpleFeature> styledCollection = vectorDataNode.getFeatureCollection();
                String defaultCSS = vectorDataNode.getDefaultStyleCss();
                SLDUtils.applyStyle(styles[0], defaultCSS, featureCollection, styledCollection);
                return vectorDataNode;
            } else {
                return new VectorDataNode(name, featureCollection);
            }
        }

        private class MyFeatureCrsProvider implements FeatureUtils.FeatureCrsProvider {

            private final VisatApp visatApp;
            private final String helpId;

            public MyFeatureCrsProvider(VisatApp visatApp, String helpId) {
                this.visatApp = visatApp;
                this.helpId = helpId;
            }

            @Override
            public CoordinateReferenceSystem getCrs(final Product product, final FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection) {
                final CoordinateReferenceSystem[] featureCrsBuffer = new CoordinateReferenceSystem[1];
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        featureCrsBuffer[0] = promptForFeatureCrs(visatApp, product);
                    }
                };
                if (!SwingUtilities.isEventDispatchThread()) {
                    try {
                        SwingUtilities.invokeAndWait(runnable);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    runnable.run();
                }
                CoordinateReferenceSystem featureCrs = featureCrsBuffer[0];
                return featureCrs != null ? featureCrs : DefaultGeographicCRS.WGS84;
            }

            private CoordinateReferenceSystem promptForFeatureCrs(VisatApp visatApp, Product product) {
                final ProductCrsForm productCrsForm = new ProductCrsForm(visatApp, product);
                final CustomCrsForm customCrsForm = new CustomCrsForm(visatApp);
                final PredefinedCrsForm predefinedCrsForm = new PredefinedCrsForm(visatApp);

                final CrsSelectionPanel crsSelectionPanel = new CrsSelectionPanel(productCrsForm,
                                                                                  customCrsForm,
                                                                                  predefinedCrsForm);
                final ModalDialog dialog = new ModalDialog(visatApp.getApplicationWindow(), importer.getDialogTitle(),
                                                           ModalDialog.ID_OK_CANCEL_HELP, helpId);

                final TableLayout tableLayout = new TableLayout(1);
                tableLayout.setTableWeightX(1.0);
                tableLayout.setTableFill(TableLayout.Fill.BOTH);
                tableLayout.setTablePadding(4, 4);
                tableLayout.setCellPadding(0, 0, new Insets(4, 10, 4, 4));
                final JPanel contentPanel = new JPanel(tableLayout);
                final JLabel label = new JLabel();
                label.setText("<html><b>" +
                              "This Shapefile does not define a coordinate reference system (CRS).<br/>" +
                              "Please specify a CRS so that coordinates can interpreted correctly.</b>");

                contentPanel.add(label);
                contentPanel.add(crsSelectionPanel);
                dialog.setContent(contentPanel);
                if (dialog.show() == ModalDialog.ID_OK) {
                    try {
                        return crsSelectionPanel.getCrs(ProductUtils.getCenterGeoPos(product));
                    } catch (FactoryException e) {
                        visatApp.showErrorDialog(importer.getDialogTitle(),
                                                 "Can not create Coordinate Reference System.\n" + e.getMessage());
                    }
                }
                return null;
            }
        }
    }


}