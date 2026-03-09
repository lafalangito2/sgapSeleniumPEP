package selenium;

import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.*;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConteoWebyMarcar_OK3_CLEAN2
 *
 * + CASO ESPECIAL (fallback):
 *   Si NO se consigue combinación usando match por OEPs,
 *   entonces se intenta una combinación usando SOLO la especialidad (ignorando OEPs),
 *   siempre sobre los checks existentes de esa especialidad, para sumar plazasExcel.
 *
 * Mantiene:
 *  - Regla OEP: si la OEP es SOLO "2023" => también buscar "2022"
 *  - Expansión año suelto: "YYYY" => también "YYYY-ESTABILIZACIÓN"
 *  - Escribe el resultado en columna con cabecera = nodeId (crea si no existe)
 *  - Backup .bak + tmp + replace
 *
 * Regex/escapes revisados.
 */
public class ConteoWebyMarcar_OK3_CLEAN2 {

  private static final boolean ENABLE_FALLBACK_SOLO_ESPECIALIDAD = false;




  private static final String BASE = "https://portalempleopublicoadm.juntadeandalucia.es";
  private static final String EXCEL_PATH_DEFAULT = "D:\\Descargas\\conteoPlazas2022_Estabilizacion.xlsx";
  private static final String SHEET_NAME = null; // null => primera hoja

  private final WebDriver driver;
  private final WebDriverWait wait;

  public ConteoWebyMarcar_OK3_CLEAN2() {
    ChromeOptions options = new ChromeOptions();
    options.setExperimentalOption("debuggerAddress", "localhost:9222");
    driver = new ChromeDriver(options);
    wait = new WebDriverWait(driver, Duration.ofSeconds(30));
  }

  public static void main(String[] args) {
    String excelPath = (args != null && args.length > 0 && args[0] != null && !args[0].isBlank())
        ? args[0]
        : EXCEL_PATH_DEFAULT;

    ConteoWebyMarcar_OK3_CLEAN2 app = new ConteoWebyMarcar_OK3_CLEAN2();
    try {
      System.out.println("🚀 Ejecutando ConteoWebyMarcar_OK3_CLEAN2");
      System.out.println("   excel=" + excelPath);
      app.ejecutarDesdeExcel(excelPath);
    } finally {
      try { app.driver.quit(); } catch (Exception ignored) {}
    }
  }

  // =========================================================
  // EJECUCIÓN DESDE EXCEL
  // =========================================================
  public void ejecutarDesdeExcel(String excelPath) {
    Path p = Paths.get(excelPath);
    if (!Files.exists(p)) throw new RuntimeException("❌ No existe el Excel: " + p.toAbsolutePath());

    try {
      byte[] excelBytes = Files.readAllBytes(p);

      try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
        Sheet sh = (SHEET_NAME == null) ? wb.getSheetAt(0) : wb.getSheet(SHEET_NAME);
        if (sh == null) throw new RuntimeException("❌ No encuentro la hoja en el Excel.");

        Iterator<Row> it = sh.rowIterator();
        if (!it.hasNext()) {
          System.out.println("⚠ Excel sin filas.");
          return;
        }

        Row header = it.next();
        Map<String, Integer> idx = headerIndex(header);

        Integer iNode = idx.getOrDefault("node", null);
        if (iNode == null) iNode = idx.getOrDefault("node_id", null);
        if (iNode == null) throw new RuntimeException("❌ No encuentro columna 'node' (ni 'node_id').");

        int iOeps = col(idx, "oeps");
        int iEsp  = col(idx, "especialidad");
        int iPlz  = col(idx, "plazas");

        DataFormatter fmt = new DataFormatter();

        while (it.hasNext()) {
          Row row = it.next();

          String node = getCellString(fmt, row.getCell(iNode));
          String oepsRaw = getCellString(fmt, row.getCell(iOeps));
          String especialidad = getCellString(fmt, row.getCell(iEsp));
          int plazasExcel = parseIntSafe(getCellString(fmt, row.getCell(iPlz)), -1);

          String resultado;
          if (node.isBlank()) {
            resultado = "SKIP: node vacío";
            escribirResultadoEnColumnaNodo(header, node, row, resultado);
            continue;
          }

          try {
            String espCodigo = extraerPrimerNumero(especialidad);
            if (espCodigo.isBlank()) {
              resultado = "FAIL: sin código especialidad";
            } else {
              Set<String> oeps = splitOepsUnicosConExpansion(oepsRaw);
              resultado = ejecutarNodoYMarcarPorCombinacion(node, espCodigo, oeps, plazasExcel);
            }
          } catch (Exception e) {
            resultado = "ERROR: " + recortar(e.getMessage(), 180);
            manejarError("Error procesando node=" + node, e);
          }

          escribirResultadoEnColumnaNodo(header, node, row, resultado);
        }

        // Backup + save
        Path bak = Paths.get(excelPath + ".bak");
        try { Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}

        Path tmp = Paths.get(excelPath + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
          wb.write(fos);
        }

        try {
          Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
          Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
      }

    } catch (Exception e) {
      throw new RuntimeException("❌ Error leyendo/escribiendo Excel: " + e.getMessage(), e);
    }
  }

  // =========================================================
  // ESCRITURA EN COLUMNA "nodeId"
  // =========================================================
  private void escribirResultadoEnColumnaNodo(Row header, String nodeId, Row row, String texto) {
    int col = obtenerOcrearColumnaPorHeader(header, (nodeId == null ? "" : nodeId.trim()));
    Cell c = row.getCell(col, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    c.setCellValue(texto == null ? "" : texto);
  }

  private int obtenerOcrearColumnaPorHeader(Row header, String headerName) {
    String name = (headerName == null || headerName.isBlank()) ? "RESULTADO" : headerName;

    DataFormatter fmt = new DataFormatter();
    int last = header.getLastCellNum();
    if (last < 0) last = 0;

    for (int c = 0; c < last; c++) {
      Cell hc = header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      if (hc == null) continue;
      String val = fmt.formatCellValue(hc).trim();
      if (val.equals(name)) return c;
    }

    int newCol = last;
    Cell hcNew = header.createCell(newCol);
    hcNew.setCellValue(name);
    return newCol;
  }

  // =========================================================
  // LÓGICA POR NODO (incluye fallback especialidad)
  // =========================================================
  private String ejecutarNodoYMarcarPorCombinacion(String nodeId, String espCodigo, Set<String> oeps, int plazasExcel) {
    String url = BASE + "/node/" + nodeId + "/edit/";
    driver.get(url);

    wait.until(ExpectedConditions.presenceOfElementLocated(
        By.cssSelector("form#node-proceso-selectivo-edit-form, form.node-form, form.node-edit-form")));

    esperarDrupalAjaxTermine();
    abrirTabCategorizacionYEsperarChecklist(nodeId);

    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

    if (plazasExcel < 0) return "FAIL: plazasExcel inválido";

    // 1) Intento normal: por OEP + especialidad (si hay oeps)
    if (oeps != null && !oeps.isEmpty()) {
      String res = intentarMarcarConjunto(nodeId, espCodigo, plazasExcel, true, oeps);
      if (res.startsWith("OK") || res.startsWith("NOOP")) return res;

      // Solo hacemos fallback si el fallo fue por falta de match/combinación.
      if (res.startsWith("FAIL: sin combinación") || res.startsWith("FAIL: sin candidatos")) {
        System.out.println("↩ Fallback: intentando por especialidad (ignorando OEPs)...");
      } else {
        return res;
      }
    }

    // 2) Fallback: solo especialidad (ignorar OEPs)
  //  return intentarMarcarConjunto(nodeId, espCodigo, plazasExcel, false, Collections.emptySet());

  if (!ENABLE_FALLBACK_SOLO_ESPECIALIDAD) {
  return "FAIL: sin combinación / sin candidatos (fallback deshabilitado)";
}
else
return intentarMarcarConjunto(nodeId, espCodigo, plazasExcel, false, Collections.emptySet());
  }

  private String intentarMarcarConjunto(String nodeId, String espCodigo, int plazasExcel, boolean filtrarPorOep, Set<String> oeps) {
    MatchResult mr = filtrarPorOep ? recolectarCandidatosPorOepYEspecialidad(espCodigo, oeps)
                                   : recolectarCandidatosSoloEspecialidad(espCodigo);

    if (mr.items.isEmpty()) return "FAIL: sin candidatos" + (filtrarPorOep ? "" : " (fallback)");

    int sumaMarcados = 0;
    List<ChecklistItem> unchecked = new ArrayList<>();
    for (ChecklistItem it : mr.items) {
      if (it.selected) sumaMarcados += it.plazas;
      else unchecked.add(it);
    }

    if (sumaMarcados == plazasExcel) return "NOOP: ya cuadra (" + sumaMarcados + ")" + (filtrarPorOep ? "" : " (fallback)");
    if (sumaMarcados > plazasExcel) return "FAIL: marcados(" + sumaMarcados + ")>excel(" + plazasExcel + ")" + (filtrarPorOep ? "" : " (fallback)");

    int objetivoRestante = plazasExcel - sumaMarcados;
    List<ChecklistItem> aMarcar = buscarSubconjuntoPorSuma(unchecked, objetivoRestante);
    if (aMarcar == null || aMarcar.isEmpty()) return "FAIL: sin combinación (" + objetivoRestante + ")" + (filtrarPorOep ? "" : " (fallback)");

    int marcados = 0;
    for (ChecklistItem it : aMarcar) {
      if (!it.selected) {
        marcarCheckboxPorId(it.checkboxId);
        marcados++;
      }
    }

    if (marcados > 0) {
      try { Thread.sleep(500); } catch (InterruptedException ignored) {}
      boolean ok = publicarYGuardarYEsperar("node=" + nodeId);
      if (!ok) return "FAIL: guardar" + (filtrarPorOep ? "" : " (fallback)");
      return "OK: marcados=" + marcados + (filtrarPorOep ? "" : " (fallback)");
    }

    return "NOOP: combinación ya marcada" + (filtrarPorOep ? "" : " (fallback)");
  }

  // =========================================================
  // CANDIDATOS
  // =========================================================
  private MatchResult recolectarCandidatosPorOepYEspecialidad(String espCodigo, Set<String> oeps) {
    MatchResult out = new MatchResult();

    By allBy = By.cssSelector("#edit-group-categorizacion #edit-field-oferta-de-plazas input[type='checkbox'][id^='edit-field-oferta-de-plazas-']");
    List<WebElement> all = driver.findElements(allBy);

    for (WebElement cb : all) {
      String id = cb.getAttribute("id");
      if (id == null || id.isBlank()) continue;

      String labelText;
      try {
        WebElement label = driver.findElement(By.cssSelector("label[for='" + cssEscape(id) + "']"));
        labelText = label.getText();
      } catch (Exception ignored) {
        continue;
      }
      if (labelText == null || labelText.isBlank()) continue;

      boolean matchOep = false;
      for (String o : oeps) {
        if (o != null && !o.isBlank() && labelText.contains(o.trim())) {
          matchOep = true;
          break;
        }
      }
      if (!matchOep) continue;

      if (!contieneCodigo(labelText, espCodigo)) continue;

      int plazasItem = sumarPlazasDesdeLabel(labelText);

      ChecklistItem it = new ChecklistItem();
      it.checkboxId = id;
      it.plazas = plazasItem;
      it.selected = safeIsSelected(cb);

      out.items.add(it);
      out.sumaPlazas += plazasItem;
    }

    return out;
  }

  // Fallback: ignora OEPs, solo especialidad
  private MatchResult recolectarCandidatosSoloEspecialidad(String espCodigo) {
    MatchResult out = new MatchResult();

    By allBy = By.cssSelector("#edit-group-categorizacion #edit-field-oferta-de-plazas input[type='checkbox'][id^='edit-field-oferta-de-plazas-']");
    List<WebElement> all = driver.findElements(allBy);

    for (WebElement cb : all) {
      String id = cb.getAttribute("id");
      if (id == null || id.isBlank()) continue;

      String labelText;
      try {
        WebElement label = driver.findElement(By.cssSelector("label[for='" + cssEscape(id) + "']"));
        labelText = label.getText();
      } catch (Exception ignored) {
        continue;
      }
      if (labelText == null || labelText.isBlank()) continue;

      if (!contieneCodigo(labelText, espCodigo)) continue;

      int plazasItem = sumarPlazasDesdeLabel(labelText);

      ChecklistItem it = new ChecklistItem();
      it.checkboxId = id;
      it.plazas = plazasItem;
      it.selected = safeIsSelected(cb);

      out.items.add(it);
      out.sumaPlazas += plazasItem;
    }

    return out;
  }

  private boolean safeIsSelected(WebElement cb) {
    try { return cb.isSelected(); } catch (Exception e) { return false; }
  }

  private boolean contieneCodigo(String labelText, String code) {
    Pattern p = Pattern.compile("(?<!\\d)" + Pattern.quote(code) + "(?!\\d)");
    return p.matcher(labelText).find();
  }

  private int sumarPlazasDesdeLabel(String labelText) {
    int sum = 0;

    Matcher m = Pattern.compile(":\\s*(\\d+)").matcher(labelText);
    while (m.find()) sum += parseIntSafe(m.group(1), 0);

    Matcher m2 = Pattern.compile("(?<!:)\\s(\\d+)\\s*$").matcher(labelText.trim());
    if (m2.find()) sum += parseIntSafe(m2.group(1), 0);

    return sum;
  }

  private void marcarCheckboxPorId(String checkboxId) {
    try {
      WebElement cb = driver.findElement(By.id(checkboxId));
      if (cb.isSelected()) return;

      scrollToCenter(cb);
      safeClick(cb);

      String finalId = checkboxId;
      new WebDriverWait(driver, Duration.ofSeconds(10))
          .ignoring(StaleElementReferenceException.class)
          .until(d -> {
            try { return d.findElement(By.id(finalId)).isSelected(); }
            catch (Exception e) { return false; }
          });

      esperarDrupalAjaxTermine();
    } catch (Exception ignored) {}
  }

  // =========================================================
  // SUBSET-SUM
  // =========================================================
  private List<ChecklistItem> buscarSubconjuntoPorSuma(List<ChecklistItem> items, int objetivo) {
    if (objetivo <= 0) return Collections.emptyList();
    if (items == null || items.isEmpty()) return null;

    class State { int prevSum, index, count; State(int p, int i, int c){prevSum=p;index=i;count=c;} }
    Map<Integer, State> best = new HashMap<>();
    best.put(0, new State(-1, -1, 0));

    for (int i = 0; i < items.size(); i++) {
      int w = Math.max(0, items.get(i).plazas);
      if (w == 0) continue;

      List<Integer> sums = new ArrayList<>(best.keySet());
      for (int s : sums) {
        int ns = s + w;
        if (ns > objetivo) continue;

        State prev = best.get(s);
        int newCount = prev.count + 1;

        State cur = best.get(ns);
        if (cur == null || newCount < cur.count) {
          best.put(ns, new State(s, i, newCount));
        }
      }
    }

    if (!best.containsKey(objetivo)) return null;

    List<ChecklistItem> res = new ArrayList<>();
    int s = objetivo;
    while (s != 0) {
      State st = best.get(s);
      if (st == null || st.index < 0) break;
      res.add(items.get(st.index));
      s = st.prevSum;
    }
    Collections.reverse(res);
    return res;
  }

  private static class ChecklistItem {
    String checkboxId;
    int plazas;
    boolean selected;
  }

  private static class MatchResult {
    List<ChecklistItem> items = new ArrayList<>();
    int sumaPlazas = 0;
  }

  // =========================================================
  // TAB CATEGORIZACIÓN + CHECKLIST (base)
  // =========================================================
  private void abrirTabCategorizacionYEsperarChecklist(String nodeId) {

    WebElement tabLink = encontrarTabCategorizacion();
    if (tabLink == null) {
      dumpPageSource("NO_ENCUENTRO_TAB_categorizacion_" + nodeId);
      throw new TimeoutException("No encuentro el enlace del tab 'Categorización y ofertas'.");
    }

    scrollToCenter(tabLink);
    safeClick(tabLink);

    By detailsBy = By.cssSelector("details#edit-group-categorizacion");
    WebElement details = wait.until(ExpectedConditions.presenceOfElementLocated(detailsBy));

    new WebDriverWait(driver, Duration.ofSeconds(30))
        .ignoring(StaleElementReferenceException.class)
        .until(d -> {
          try {
            WebElement det = d.findElement(detailsBy);
            String openAttr = det.getAttribute("open");
            WebElement summary = det.findElement(By.cssSelector("summary"));
            String expanded = summary.getAttribute("aria-expanded");
            return (openAttr != null) || "true".equalsIgnoreCase(expanded);
          } catch (Exception e) { return false; }
        });

    esperarDrupalAjaxTermine();

    By rootBy = By.cssSelector("#edit-group-categorizacion #edit-field-oferta-de-plazas");
    wait.until(ExpectedConditions.presenceOfElementLocated(rootBy));

    By checkboxBy = By.cssSelector("#edit-group-categorizacion #edit-field-oferta-de-plazas input[type='checkbox'][id^='edit-field-oferta-de-plazas-']");
    By emptyBy = By.cssSelector("#edit-group-categorizacion #edit-field-oferta-de-plazas .views-empty, " +
        "#edit-group-categorizacion #edit-field-oferta-de-plazas .view-empty, " +
        "#edit-group-categorizacion #edit-field-oferta-de-plazas .no-results");

    try {
      new WebDriverWait(driver, Duration.ofSeconds(60))
          .ignoring(StaleElementReferenceException.class)
          .until(d -> !d.findElements(checkboxBy).isEmpty() || !d.findElements(emptyBy).isEmpty());
    } catch (TimeoutException te) {
      dumpElementOuterHtml("timeout_details_categorizacion_" + nodeId, details);
      dumpPageSource("timeout_page_categorizacion_" + nodeId);
      throw te;
    }

    esperarDrupalAjaxTermine();
  }

  private WebElement encontrarTabCategorizacion() {
    By css = By.cssSelector(
        "li.vertical-tabs__menu-item a[href='#edit-group-categorizacion'], " +
        "a.vertical-tabs__menu-link[href='#edit-group-categorizacion']"
    );

    List<WebElement> list = driver.findElements(css);
    if (!list.isEmpty()) return list.get(0);

    By xpath = By.xpath("//li[contains(@class,'vertical-tabs__menu-item')]//a[contains(@href,'#edit-group-categorizacion') or contains(@class,'vertical-tabs__menu-link')][.//strong[contains(normalize-space(.),'Categorización')]]");
    list = driver.findElements(xpath);
    if (!list.isEmpty()) return list.get(0);

    By xpath2 = By.xpath("//a[contains(@href,'#edit-group-categorizacion')][contains(normalize-space(.),'Categorización')]");
    list = driver.findElements(xpath2);
    if (!list.isEmpty()) return list.get(0);

    return null;
  }

  // =========================================================
  // GUARDAR + PUBLICAR
  // =========================================================
  public boolean publicarYGuardarYEsperar(String organismo) {
    try {
      By publicadoBy = By.cssSelector("input#edit-status-value[data-drupal-selector='edit-status-value']");
      By guardarBy = By.cssSelector("input#gin-sticky-edit-submit[data-drupal-selector='gin-sticky-edit-submit']");

      By errorMsgBy = By.cssSelector(".messages__wrapper .messages--error");
      By okMsgBy = By.cssSelector(".messages__wrapper .messages--status, .messages__wrapper .messages--success");

      WebElement chkPublicado = wait.until(ExpectedConditions.presenceOfElementLocated(publicadoBy));
      WebElement btnGuardar = wait.until(ExpectedConditions.elementToBeClickable(guardarBy));

      if (!chkPublicado.isSelected()) {
        scrollToCenter(chkPublicado);
        safeClickAjax(chkPublicado);

        new WebDriverWait(driver, Duration.ofSeconds(10))
            .ignoring(StaleElementReferenceException.class)
            .until(d -> {
              try { return d.findElement(publicadoBy).isSelected(); }
              catch (Exception e) { return false; }
            });
      }

      String urlAntes = driver.getCurrentUrl();

      scrollToCenter(btnGuardar);
      safeClickAjax(btnGuardar);

      WebDriverWait waitSave = new WebDriverWait(driver, Duration.ofSeconds(90));
      waitSave.ignoring(StaleElementReferenceException.class).until(d -> {
        if (!d.findElements(errorMsgBy).isEmpty()) return true;
        if (!d.findElements(okMsgBy).isEmpty()) return true;
        String now = "";
        try { now = d.getCurrentUrl(); } catch (Exception ignored) {}
        return now != null && !now.equals(urlAntes);
      });

      esperarDrupalAjaxTermine();
      return driver.findElements(errorMsgBy).isEmpty();

    } catch (Exception e) {
      return false;
    }
  }

  private void safeClickAjax(WebElement el) {
    try { el.click(); }
    catch (Exception ex) {
      ((JavascriptExecutor) driver).executeScript(
          "var e=arguments[0];" +
              "['mouseover','mousedown','mouseup','click'].forEach(function(ev){" +
              "  e.dispatchEvent(new MouseEvent(ev,{view:window,bubbles:true,cancelable:true}));" +
              "});", el);
    }
  }

  // =========================================================
  // OEPs: split + UNIQUE + EXPANSIÓN (incluye regla 2023->2022)
  // =========================================================
  private Set<String> splitOepsUnicosConExpansion(String raw) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (raw == null) return out;

    String[] parts = raw.split(",");
    for (String p : parts) {
      String t = (p == null) ? "" : p.trim();
      if (t.isBlank()) continue;
      out.add(t);
    }

    // Regla: si es SOLO "2023" => también "2022"
    if (out.size() == 1 && out.contains("2023")) {
      out.add("2022");
    }

    // Expansión: "YYYY" => "YYYY-ESTABILIZACIÓN"
    List<String> snapshot = new ArrayList<>(out);
    for (String t : snapshot) {
      if (t != null && t.matches("^\\d{4}$")) {
        out.add(t + "-ESTABILIZACIÓN");
      }
    }

    return out;
  }

  // =========================================================
  // Excel helpers
  // =========================================================
  private Map<String, Integer> headerIndex(Row header) {
    Map<String, Integer> m = new HashMap<>();
    DataFormatter fmt = new DataFormatter();
    for (Cell c : header) {
      String key = fmt.formatCellValue(c).trim().toLowerCase(Locale.ROOT);
      if (!key.isEmpty()) m.put(key, c.getColumnIndex());
    }
    return m;
  }

  private int col(Map<String, Integer> idx, String name) {
    Integer i = idx.get(name.toLowerCase(Locale.ROOT));
    if (i == null) throw new RuntimeException("❌ No encuentro columna '" + name + "' en el Excel.");
    return i;
  }

  private String getCellString(DataFormatter fmt, Cell c) {
    if (c == null) return "";
    return fmt.formatCellValue(c).trim();
  }

  // =========================================================
  // Utilidades
  // =========================================================
  private String extraerPrimerNumero(String s) {
    if (s == null) return "";
    Matcher m = Pattern.compile("(\\d+)").matcher(s);
    return m.find() ? m.group(1) : "";
  }

  private int parseIntSafe(String s, int def) {
    try {
      if (s == null) return def;
      String t = s.trim().replace(".", "").replace(",", ".");
      if (t.contains(".")) t = t.substring(0, t.indexOf('.'));
      return Integer.parseInt(t);
    } catch (Exception e) {
      return def;
    }
  }

  private String recortar(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  // =========================================================
  // AJAX + Helpers Selenium
  // =========================================================
  private void esperarDrupalAjaxTermine() {
    new WebDriverWait(driver, Duration.ofSeconds(60))
        .ignoring(StaleElementReferenceException.class)
        .until(d -> {
          try {
            boolean throbbers = !d.findElements(By.cssSelector(".ajax-progress, .ajax-throbber")).isEmpty();
            if (throbbers) return false;

            Object jqActive = ((JavascriptExecutor) d).executeScript(
                "return (window.jQuery && typeof jQuery.active === 'number') ? jQuery.active : 0;");
            if (jqActive instanceof Number) return ((Number) jqActive).intValue() == 0;

            return true;
          } catch (Exception e) {
            return true;
          }
        });
  }

  private void scrollToCenter(WebElement el) {
    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", el);
  }

  private void safeClick(WebElement el) {
    try { el.click(); }
    catch (Exception ex) {
      ((JavascriptExecutor) driver).executeScript(
          "var e=arguments[0];" +
              "['mouseover','mousedown','mouseup','click'].forEach(function(ev){" +
              "  e.dispatchEvent(new MouseEvent(ev,{view:window,bubbles:true,cancelable:true}));" +
              "});", el);
    }
  }

  private String cssEscape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("'", "\\'");
  }

  private void dumpPageSource(String prefix) {
    try {
      String html = driver.getPageSource();
      String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
      Path out = Paths.get(prefix + "_" + ts + ".html");
      Files.writeString(out, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception ignored) {}
  }

  private void dumpElementOuterHtml(String prefix, WebElement el) {
    try {
      String html = (String) ((JavascriptExecutor) driver).executeScript("return arguments[0].outerHTML;", el);
      String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
      Path out = Paths.get(prefix + "_" + ts + ".html");
      Files.writeString(out, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception ignored) {}
  }

  private void manejarError(String mensaje, Exception e) {
    System.out.println("❌ " + mensaje + " | " + (e.getMessage() == null ? "" : e.getMessage()));
    e.printStackTrace();
  }
}
