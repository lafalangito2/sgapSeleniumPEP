package selenium;

import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.*;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * ConteoPlazasDesdeExcel:
 * - Lee conteoPlazas.xlsx con columnas: node, nodo, plazas, plazasweb
 * - Por cada fila:
 *   - Abre https://portalempleopublicoadm.juntadeandalucia.es/node/{node}
 *   - Lee plazas web desde:
 *       div.field--name-field-plazas-totales .field__item
 *   - Compara plazas (excel) vs plazasweb (web)
 *   - Si distinto -> resultado=FAIL, si igual -> resultado=OK
 * - Escribe plazasweb y resultado en el Excel y lo guarda.
 *
 * Requiere Chrome abierto en modo debug (igual que tu clase):
 *   chrome.exe --remote-debugging-port=9222 --user-data-dir="C:\chrome-selenium"
 */
public class ConteoPlazasDesdeExcel {

  private static final String BASE = "https://portalempleopublicoadm.juntadeandalucia.es";
  //private static final String EXCEL_PATH_DEFAULT = "D:\\conteoPlazas.xlsx";
  private static final String EXCEL_PATH_DEFAULT = "D:\\Descargas\\PlazasPublicados.xlsx";
  
  private static final String SHEET_NAME = null; // null => primera hoja

  private final WebDriver driver;
  private final WebDriverWait wait;

  public ConteoPlazasDesdeExcel() {
    ChromeOptions options = new ChromeOptions();
    options.setExperimentalOption("debuggerAddress", "localhost:9222");
    driver = new ChromeDriver(options);
    wait = new WebDriverWait(driver, Duration.ofSeconds(30));
  }

  public static void main(String[] args) {
    String excelPath = (args != null && args.length > 0 && args[0] != null && !args[0].isBlank())
        ? args[0]
        : EXCEL_PATH_DEFAULT;

    ConteoPlazasDesdeExcel app = new ConteoPlazasDesdeExcel();
    try {
      System.out.println("🚀 Ejecutando ConteoPlazasDesdeExcel");
      System.out.println("   excel=" + excelPath);
      app.ejecutar(excelPath);
    } finally {
      try { app.driver.quit(); } catch (Exception ignored) {}
    }
  }

  public void ejecutar(String excelPath) {
    Path p = Paths.get(excelPath);
    if (!Files.exists(p)) throw new RuntimeException("❌ No existe el Excel: " + p.toAbsolutePath());

    try (FileInputStream fis = new FileInputStream(excelPath);
         Workbook wb = WorkbookFactory.create(fis)) {

      Sheet sh = (SHEET_NAME == null) ? wb.getSheetAt(0) : wb.getSheet(SHEET_NAME);
      if (sh == null) throw new RuntimeException("❌ No encuentro la hoja en el Excel.");

      if (sh.getPhysicalNumberOfRows() == 0) {
        System.out.println("⚠ Excel vacío.");
        return;
      }

      DataFormatter fmt = new DataFormatter();

      Row header = sh.getRow(sh.getFirstRowNum());
      if (header == null) throw new RuntimeException("❌ No hay cabecera en la primera fila.");

      Map<String, Integer> idx = headerIndex(header, fmt);

      int iNode = requireCol(idx, "node");
      int iPlazas = requireCol(idx, "plazas");

      // plazasweb puede existir o no; si no existe, la creamos
      int iPlazasWeb = ensureCol(sh, header, idx, "plazasweb");
      int iResultado = ensureCol(sh, header, idx, "resultado");

      int ok = 0, fail = 0, total = 0;

      for (int r = header.getRowNum() + 1; r <= sh.getLastRowNum(); r++) {
        Row row = sh.getRow(r);
        if (row == null) continue;

        String nodeStr = getCellString(fmt, row.getCell(iNode));
        if (nodeStr.isBlank()) continue;

        total++;
        String plazasStr = getCellString(fmt, row.getCell(iPlazas));

        System.out.println("\n------------------------------------");
        System.out.println("➡ Fila " + (r + 1) + " | node=" + nodeStr + " | plazas(excel)=" + plazasStr);

        try {
          String plazasWebStr = leerPlazasWeb(nodeStr);
          System.out.println("🌐 plazas(web)=" + plazasWebStr);

          // escribimos plazasweb
          setCellString(row, iPlazasWeb, plazasWebStr);

          Integer pExcel = parseEntero(plazasStr);
          Integer pWeb = parseEntero(plazasWebStr);

          String resultado;
          if (pExcel == null || pWeb == null) {
            // Si no se puede comparar numéricamente, lo tratamos como FAIL
            resultado = "FAIL";
          } else {
            resultado = Objects.equals(pExcel, pWeb) ? "OK" : "FAIL";
          }

          setCellString(row, iResultado, resultado);

          if ("OK".equalsIgnoreCase(resultado)) ok++; else fail++;
          System.out.println("✅ resultado=" + resultado);

        } catch (Exception e) {
          // En error, marcamos FAIL
          setCellString(row, iResultado, "FAIL");
          fail++;
          System.out.println("❌ Error en node=" + nodeStr + " -> FAIL");
          System.out.println("   " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }

      // Guardar sobre el mismo fichero
      try (FileOutputStream fos = new FileOutputStream(excelPath)) {
        wb.write(fos);
      }

      System.out.println("\n=== RESUMEN ===");
      System.out.println("Total procesadas: " + total);
      System.out.println("OK:   " + ok);
      System.out.println("FAIL: " + fail);
      System.out.println("💾 Excel guardado: " + Paths.get(excelPath).toAbsolutePath());

    } catch (Exception e) {
      throw new RuntimeException("❌ Error procesando Excel: " + e.getMessage(), e);
    }
  }

private String leerPlazasWeb(String nodeId) {
  String url = BASE + "/node/" + nodeId;

  By itemBy = By.cssSelector("div.field--name-field-plazas-totales .field__item");

  driver.get(url);
  dormir(300);

  // 0 = primera carga, 1..3 = refresh hasta 3 veces
  for (int intento = 0; intento <= 3; intento++) {

    // esperar a que el elemento exista y sea visible
    WebElement item = new WebDriverWait(driver, Duration.ofSeconds(30))
        .until(ExpectedConditions.visibilityOfElementLocated(itemBy));

    String txt = (item.getText() == null) ? "" : item.getText().trim();
    Integer n = parseEntero(txt);

    // si no es numérico, devolvemos lo que venga (y no refrescamos)a
    if (n == null) return txt;

    // si NO es 0, nos quedamos con ese valor
    if (n != 0) return String.valueOf(n);

    // si es 0 y aún quedan refrescos, refrescamos
    if (intento < 3) {
      driver.navigate().refresh();
      dormir(1000);
    }
  }

  // si tras 3 refresh sigue siendo 0, devolvemos 0
  return "0";
}

private void dormir(long ms) {
  try {
    Thread.sleep(ms);
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  }
}

  // =========================
  // Excel helpers
  // =========================
  private Map<String, Integer> headerIndex(Row header, DataFormatter fmt) {
    Map<String, Integer> m = new HashMap<>();
    for (Cell c : header) {
      String key = fmt.formatCellValue(c).trim().toLowerCase(Locale.ROOT);
      if (!key.isEmpty()) m.put(key, c.getColumnIndex());
    }
    return m;
  }

  private int requireCol(Map<String, Integer> idx, String name) {
    Integer i = idx.get(name.toLowerCase(Locale.ROOT));
    if (i == null) throw new RuntimeException("❌ No encuentro columna '" + name + "' en el Excel.");
    return i;
  }

  private int ensureCol(Sheet sh, Row header, Map<String, Integer> idx, String colName) {
    String key = colName.toLowerCase(Locale.ROOT);
    Integer existing = idx.get(key);
    if (existing != null) return existing;

    int newCol = header.getLastCellNum();
    if (newCol < 0) newCol = 0;

    Cell hc = header.createCell(newCol, CellType.STRING);
    hc.setCellValue(colName);

    idx.put(key, newCol);

    // opcional: autosize (no siempre funciona bien, pero no molesta)
    try { sh.autoSizeColumn(newCol); } catch (Exception ignored) {}

    return newCol;
  }

  private String getCellString(DataFormatter fmt, Cell c) {
    if (c == null) return "";
    return fmt.formatCellValue(c).trim();
  }

  private void setCellString(Row row, int col, String value) {
    Cell c = row.getCell(col);
    if (c == null) c = row.createCell(col, CellType.STRING);
    c.setCellValue(value == null ? "" : value);
  }

  private Integer parseEntero(String s) {
    if (s == null) return null;
    String t = s.trim();
    if (t.isEmpty()) return null;

    // extrae el primer bloque de dígitos
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
    if (!m.find()) return null;

    try {
      return Integer.parseInt(m.group(1));
    } catch (Exception e) {
      return null;
    }
  
  
  }
}