/*
 * Copyright 2017 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.mcp.impl;

import com.adobe.acs.commons.mcp.model.GenericReport;
import com.day.cq.commons.jcr.JcrUtil;
import java.io.IOException;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.slf4j.LoggerFactory;

/**
 * Export a generic report as an excel spreadsheet
 */
@SlingServlet(resourceTypes = GenericReport.GENERIC_REPORT_RESOURCE_TYPE, extensions = {"xlsx","xls"})
public class GenericReportExcelServlet extends SlingSafeMethodsServlet {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GenericReportExcelServlet.class);

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        GenericReport report = request.getResource().adaptTo(GenericReport.class);
        if (report != null) {
            String title = report.getName();
            String fileName = JcrUtil.createValidName(title) + ".xlsx";

            Workbook workbook = createSpreadsheet(report);
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Expires", "0");
            response.setHeader("Cache-Control", "must-revalidate, post-check=0, pre-check=0");
            response.setHeader("Pragma", "public");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            try (ServletOutputStream out = response.getOutputStream()) {
                workbook.write(out);
                out.flush();
            } catch (Exception ex) {
                LOG.error("Error generating excel export for "+request.getResource().getPath(), ex);
                throw ex;
            }
        } else {
            LOG.error("Unable to process report stored at "+request.getResource().getPath());
            throw new ServletException("Unable to process report stored at "+request.getResource().getPath());
        }
    }
            
    private Workbook createSpreadsheet(GenericReport report) {
        Workbook wb = new XSSFWorkbook();
        
        String name = report.getName();
        for (char ch : new char[]{'\\','/','*','[',']',':','?'}) {
            name = StringUtils.remove(name, ch);
        }
        XSSFSheet sheet = (XSSFSheet) wb.createSheet(name);

        XSSFRow headerRow = sheet.createRow(0);
        
        for (int c = 0; c < report.getColumnNames().size(); c++) {
            XSSFCell headerCell = headerRow.createCell(c);
            headerCell.setCellValue(report.getColumnNames().get(c));
        }

        List<ValueMap> rows = report.getRows();
        //make rows, don't forget the header row
        for (int r = 0; r < rows.size(); r++) {
            XSSFRow row = sheet.createRow(r+1);

            //make columns
            for (int c = 0; c < report.getColumns().size(); c++) {
                String col = report.getColumns().get(c);
                XSSFCell cell = row.createCell(c);

                if (rows.get(r).containsKey(col)) {
                    Object val = rows.get(r).get(col);
                    if (val instanceof Number) {
                        Number n = (Number) val;
                        cell.setCellValue(((Number) val).doubleValue());                        
                    } else {
                        cell.setCellValue(String.valueOf(val));
                    }
                }
            }
        }
        return wb;
    }
}
