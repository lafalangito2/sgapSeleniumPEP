import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.Select;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Set;

public class arregloLABORALES {

    public static void main(String[] args) {
        String excelPath = "d:\\Descargas\\enlaces_extraidos.xlsx";

        WebDriver driver = null;
        Workbook workbook = null;
        FileInputStream fis = null;

        try {
            ChromeOptions options = new ChromeOptions();
            options.setExperimentalOption("debuggerAddress", "127.0.0.1:9222");

            driver = new ChromeDriver(options);
            JavascriptExecutor js = (JavascriptExecutor) driver;

            Set<String> handles = driver.getWindowHandles();
            if (handles == null || handles.isEmpty()) {
                driver.switchTo().newWindow(WindowType.TAB);
            } else {
                driver.switchTo().window(handles.iterator().next());
            }

            fis = new FileInputStream(excelPath);
            workbook = new XSSFWorkbook(fis);
            Sheet sheet = workbook.getSheetAt(0);

            DataFormatter formatter = new DataFormatter();

            // Cabecera
            Row header = sheet.getRow(0);
            if (header == null) header = sheet.createRow(0);
            header.createCell(7).setCellValue("update");        // H
            header.createCell(8).setCellValue("observaciones"); // I

            int procesadas = 0;
            int ok = 0;
            int fail = 0;

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Cell cellLink = row.getCell(6); // G
                String url = formatter.formatCellValue(cellLink).trim();

                Cell cellUpdate = row.getCell(7);
                if (cellUpdate == null) cellUpdate = row.createCell(7);

                Cell cellObs = row.getCell(8);
                if (cellObs == null) cellObs = row.createCell(8);

                if (url.isEmpty()) {
                    cellUpdate.setCellValue("NO");
                    cellObs.setCellValue("Columna G vacía");
                    continue;
                }

                procesadas++;
                System.out.println("====================================");
                System.out.println("Fila Excel: " + (r + 1));
                System.out.println("URL: " + url);

                try {
                    String obs = procesarNodo(driver, url);
                    cellUpdate.setCellValue("SI");
                    cellObs.setCellValue(obs);
                    ok++;
                    System.out.println("RESULTADO: OK");
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null || msg.trim().isEmpty()) {
                        msg = e.getClass().getSimpleName();
                    }
                    cellUpdate.setCellValue("NO");
                    cellObs.setCellValue(msg);
                    fail++;
                    System.out.println("RESULTADO: FAIL -> " + msg);
                }
            }

            try { fis.close(); } catch (Exception ignored) {}

            FileOutputStream fos = new FileOutputStream(excelPath);
            workbook.write(fos);
            fos.close();

            System.out.println("====================================");
            System.out.println("TOTAL procesadas: " + procesadas);
            System.out.println("OK: " + ok);
            System.out.println("FAIL: " + fail);
            System.out.println("Excel actualizado: " + excelPath);

        } catch (Exception e) {
            System.err.println("Error general");
            e.printStackTrace();
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (workbook != null) workbook.close(); } catch (Exception ignored) {}
            // if (driver != null) driver.quit();
        }
    }

    private static String procesarNodo(WebDriver driver, String url) throws Exception {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            driver.get(url);
        } catch (Exception e) {
            js.executeScript("window.location.href = arguments[0];", url);
        }

        Thread.sleep(3000);

        WebElement editarLink = driver.findElement(
                By.cssSelector("ul.nav.primary.nav-tabs a[href$='/edit']")
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", editarLink);
        Thread.sleep(500);
        js.executeScript("arguments[0].click();", editarLink);

        Thread.sleep(3000);

        WebElement selectTipoPersonal = driver.findElement(By.id("edit-field-tipo-personal"));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", selectTipoPersonal);
        Thread.sleep(500);

        try {
            Select tipoPersonal = new Select(selectTipoPersonal);
            tipoPersonal.selectByVisibleText("Personal laboral");
        } catch (Exception e) {
            js.executeScript(
                    "arguments[0].value='465';" +
                    "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
                    "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));",
                    selectTipoPersonal
            );
        }

        Thread.sleep(4000);

        WebElement totalPlazas = driver.findElement(By.id("edit-field-plazas-0-value"));
        String valorTotalPlazas = totalPlazas.getAttribute("value");

        if (valorTotalPlazas == null || valorTotalPlazas.trim().isEmpty()) {
            throw new RuntimeException("Total plazas vacío");
        }

        WebElement plazasTurnoGeneral = driver.findElement(By.id("edit-field-plazas-turno-general-0-value"));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", plazasTurnoGeneral);
        Thread.sleep(500);

        js.executeScript(
                "arguments[0].value='';" +
                "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
                "arguments[0].value=arguments[1];" +
                "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
                "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));",
                plazasTurnoGeneral, valorTotalPlazas
        );

        Thread.sleep(1000);

        String valorFinal = plazasTurnoGeneral.getAttribute("value");
        if (!valorTotalPlazas.equals(valorFinal)) {
            throw new RuntimeException("No se pudo copiar plazas. origen=" + valorTotalPlazas + ", destino=" + valorFinal);
        }

        boolean guardadoOk = publicarYGuardarYEsperar(driver);
        if (!guardadoOk) {
            throw new RuntimeException("No se pudo guardar/publicar");
        }

        return "OK. totalPlazas=" + valorTotalPlazas + ", turnoGeneral=" + valorFinal;
    }

    public static boolean publicarYGuardarYEsperar(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            Thread.sleep(2000);

            WebElement chkPublicado = driver.findElement(By.id("edit-status-value"));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", chkPublicado);
            Thread.sleep(500);

            if (!chkPublicado.isSelected()) {
                js.executeScript("arguments[0].click();", chkPublicado);
                Thread.sleep(1000);
            }

            WebElement btnGuardar = driver.findElement(By.id("gin-sticky-edit-submit"));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", btnGuardar);
            Thread.sleep(500);
            js.executeScript("arguments[0].click();", btnGuardar);

            Thread.sleep(5000);

            if (!driver.findElements(By.cssSelector(".messages__wrapper .messages--error")).isEmpty()) {
                return false;
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }
}