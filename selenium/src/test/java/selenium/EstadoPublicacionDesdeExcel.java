/*comprueba lo que esta publicado */


package selenium;

import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class EstadoPublicacionDesdeExcel {

    private static final String BASE = "https://portalempleopublicoadm.juntadeandalucia.es";

    private WebDriver driver;
    private WebDriverWait wait;

    public EstadoPublicacionDesdeExcel() {

        ChromeOptions options = new ChromeOptions();

        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");

        // bloquear imágenes (acelera mucho)
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.managed_default_content_settings.images", 2);
        options.setExperimentalOption("prefs", prefs);

        driver = new ChromeDriver(options);

        wait = new WebDriverWait(driver, Duration.ofSeconds(8));
    }

    public static void main(String[] args) throws Exception {

        String excel = "D:\\Descargas\\nodos.xlsx";

        if (args.length > 0) {
            excel = args[0];
        }

        EstadoPublicacionDesdeExcel app = new EstadoPublicacionDesdeExcel();

        app.ejecutar(excel);

        app.driver.quit();
    }

    public void ejecutar(String excelPath) throws Exception {

        FileInputStream fis = new FileInputStream(excelPath);
        Workbook wb = WorkbookFactory.create(fis);

        Sheet sheet = wb.getSheetAt(0);

        DataFormatter fmt = new DataFormatter();

        Row header = sheet.getRow(0);

        Map<String, Integer> idx = headerIndex(header, fmt);

        int colNode = idx.get("node_id");
        int colA2 = ensureCol(sheet, header, idx, "a2");

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {

            Row row = sheet.getRow(r);
            if (row == null) continue;

            String nodeId = fmt.formatCellValue(row.getCell(colNode)).trim();

            if (nodeId.isEmpty()) continue;

            System.out.println("node=" + nodeId);

            try {

                String estado = leerEstadoPublicacion(nodeId);

                setCell(row, colA2, estado);

                System.out.println("   OK -> " + estado);

            } catch (Exception e) {

                setCell(row, colA2, "ERROR");

                System.out.println("   ERROR");
            }
        }

        fis.close();

        FileOutputStream fos = new FileOutputStream(excelPath);

        wb.write(fos);

        fos.close();

        wb.close();
    }

    private String leerEstadoPublicacion(String nodeId) {

        String url = BASE + "/node/" + nodeId;

        driver.get(url);

        By selector = By.cssSelector("table.desktop tbody tr:first-child td.celda.texto");

        WebElement celda = wait.until(
                ExpectedConditions.presenceOfElementLocated(selector)
        );

        return celda.getText().trim();
    }

    private Map<String, Integer> headerIndex(Row header, DataFormatter fmt) {

        Map<String, Integer> map = new HashMap<>();

        for (Cell c : header) {

            String name = fmt.formatCellValue(c)
                    .trim()
                    .toLowerCase(Locale.ROOT);

            map.put(name, c.getColumnIndex());
        }

        return map;
    }

    private int ensureCol(Sheet sheet, Row header, Map<String, Integer> idx, String name) {

        Integer pos = idx.get(name);

        if (pos != null) return pos;

        int col = header.getLastCellNum();

        Cell cell = header.createCell(col);

        cell.setCellValue(name);

        idx.put(name, col);

        return col;
    }

    private void setCell(Row row, int col, String value) {

        Cell c = row.getCell(col);

        if (c == null) {
            c = row.createCell(col);
        }

        c.setCellValue(value);
    }
}