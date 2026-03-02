package selenium;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.*;

import ods.ArchivoAdjunto;
import ods.OdsToSelenium.PlazoComun;

/**
 * Selenium driver para CAP + ONC01/ONC02.
 *
 * - Convocatoria: ONC vacío
 * - ONC01: oferta delta 0
 * - ONC02: oferta delta 1
 *
 * Compatibilidad:
 * - plazoscomunes(...) -> ONC01
 * - introducir2Onc(...) -> ONC02
 */
public class testAPPsegOK {

  private WebDriver driver;
  private WebDriverWait wait;

  public testAPPsegOK() {
    System.out.println("=== INICIO PROCESO ===");

    ChromeOptions options = new ChromeOptions();
    options.setExperimentalOption("debuggerAddress", "localhost:9222");

    driver = new ChromeDriver(options);
    wait = new WebDriverWait(driver, Duration.ofSeconds(30));

    String urlForm = "https://portalempleopublicoadm.juntadeandalucia.es/node/add/cap";
    driver.get(urlForm);

    System.out.println("✔ URL cargada: " + driver.getCurrentUrl());
    System.out.println("✔ Título: " + driver.getTitle());
  }

  public void close() {
    try { if (driver != null) driver.quit(); } catch (Exception ignored) {}
  }

  // ---------------------------------------------------------
  // CAP
  // ---------------------------------------------------------
  public void rellenarFormularioCAP(String descripcion, String organismo, List<ArchivoAdjunto> archivos) {
    try {
      // CKEDITOR
      WebElement editor = wait.until(
          ExpectedConditions.visibilityOfElementLocated(
              By.cssSelector("div.ck-editor__editable[contenteditable='true']")));
      scrollToCenter(editor);
      editor.click();

      new Actions(driver)
          .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
          .sendKeys(Keys.DELETE)
          .sendKeys(descripcion == null ? "" : descripcion)
          .perform();

      editor.sendKeys(Keys.TAB);

      // SELECTS
      new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-ambito"))))
          .selectByVisibleText("Administración General");

      new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-cap-year"))))
          .selectByVisibleText("2025");

      WebElement tipoProvisionElement = wait.until(
          ExpectedConditions.elementToBeClickable(By.id("edit-field-tipo-de-provision")));
      Select tipoProvision = new Select(tipoProvisionElement);
      wait.until(d -> tipoProvision.getOptions().size() > 0);

      boolean seleccionado = false;
      try {
        tipoProvision.selectByVisibleText("Funcionario");
        seleccionado = true;
      } catch (org.openqa.selenium.NoSuchElementException ex) {
        // fallback
      }

      if (!seleccionado) {
        for (WebElement option : tipoProvision.getOptions()) {
          String texto = option.getText() == null ? "" : option.getText().trim();
          if (!texto.isEmpty() && !texto.toLowerCase(Locale.ROOT).contains("seleccionar")) {
            option.click();
            break;
          }
        }
      }

      new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-estado-cap"))))
          .selectByVisibleText("Finalizado");

      // LEGISLATURA + ORGANISMO (select2)
      new Select(wait.until(ExpectedConditions.elementToBeClickable(
          By.id("edit-field-organismo-legislatura-0-organigram-legislature"))))
          .selectByVisibleText("Legislatura I");

      esperarDrupalAjaxTermine();

      WebElement rendered = wait.until(
          ExpectedConditions.presenceOfElementLocated(By.cssSelector(
              "span.select2-selection__rendered[id^='select2-edit-field-organismo-legislatura-0-organigram-dependent-term']")));

      WebElement selection = rendered.findElement(
          By.xpath("./ancestor::span[contains(@class,'select2-selection')]"));

      wait.until(d -> {
        String cls = selection.getAttribute("class");
        return cls == null || !cls.contains("select2-selection--disabled");
      });

      selection.click();

      WebElement searchBox = wait.until(
          ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input.select2-search__field")));

      searchBox.sendKeys(organismo == null ? "" : organismo);
      searchBox.sendKeys(Keys.ENTER);

      // SUBIDA CONVOCATORIA (solo ONC vacío)
      if (archivos != null && !archivos.isEmpty()) {
        subirArchivosConvocatoriaRobusto(archivos);
      } else {
        System.out.println("ℹ No hay archivos para CAP.");
      }

      System.out.println("=== CAP COMPLETADO ===");

    } catch (Exception e) {
      manejarError("Error en rellenarFormularioCAP()", e);
    }
  }

  // ---------------------------------------------------------
  // Legacy (ONC01)
  // ---------------------------------------------------------
  public void plazoscomunes(String organismo, List<PlazoComun> plazosComunes, String primeraFechaOncMin) {
    System.out.println("\n================= plazoscomunes(...) =================");
    System.out.println("Organismo: " + safe(organismo));
    System.out.println("Primera fecha ONC (mínima): " + safe(primeraFechaOncMin));
    System.out.println("Plazos comunes: " + (plazosComunes == null ? 0 : plazosComunes.size()));
    System.out.println("======================================================");

    procesarOncEnOferta(0, plazosComunes, primeraFechaOncMin);
  }

  // ---------------------------------------------------------
  // Legacy (ONC02)
  // ---------------------------------------------------------
  public void introducir2Onc(List<PlazoComun> onc2) {
    System.out.println("\n================= introducir2Onc(...) =================");
    System.out.println("Plazos ONC2: " + (onc2 == null ? 0 : onc2.size()));
    System.out.println("======================================================");

    procesarOncEnOferta(1, onc2, null);
  }

  // ---------------------------------------------------------
  // ONC genérico: delta 0 (ONC01) / delta 1 (ONC02)
  // ---------------------------------------------------------
  public void procesarOncEnOferta(int ofertaDelta, List<PlazoComun> plazos, String primeraFechaOncMin) {
    System.out.println("\n=== procesarOncEnOferta delta=" + ofertaDelta + " ===");

    try {
      // Tab
      By tabOfertasBy = By.cssSelector("a.vertical-tabs__menu-link[href='#edit-group-ofertas-de-necesaria-cober']");
      WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(tabOfertasBy));
      scrollToCenter(tab);
      safeClickAjax(tab);
      wait.until(ExpectedConditions.presenceOfElementLocated(By.id("edit-group-ofertas-de-necesaria-cober")));

      // Add-more oferta
      By addMoreOfertaBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-add-more-add-more-button-ofertas-de-necesaria-coberturas'], " +
          "input#edit-field-ofertas-de-necesaria-cober-add-more-add-more-button-ofertas-de-necesaria-coberturas");

      wait.until(ExpectedConditions.presenceOfElementLocated(addMoreOfertaBy));
      asegurarOfertaDelta(ofertaDelta, addMoreOfertaBy);

      WebElement row = wait.until(ExpectedConditions.presenceOfElementLocated(ofertasRowBy(ofertaDelta)));
      scrollToCenter(row);

      // Fecha ONC
      if (primeraFechaOncMin != null && !primeraFechaOncMin.isBlank()) {
        By fechaOncBy = By.cssSelector(
            "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-fecha-onc-0-value-date'], " +
            "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-fecha-onc-0-value-date']");
        List<WebElement> fe = row.findElements(fechaOncBy);
        if (!fe.isEmpty()) {
          setDateAndCommit(fe.get(0), normalizarFecha(primeraFechaOncMin));
          esperarDrupalAjaxTermine();
        }
      }

      // Estado/Fase ONC = Finalizada (si existe)
      By estadoBy = By.cssSelector(
          "select[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-estado-fase-onc'], " +
          "select[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-estado-fase-onc']");
      List<WebElement> es = row.findElements(estadoBy);
      if (!es.isEmpty()) {
        Select sel = new Select(es.get(0));
        boolean ok = false;

        for (WebElement opt : sel.getOptions()) {
          String t = opt.getText() == null ? "" : opt.getText().trim();
          if (t.equalsIgnoreCase("Finalizada")) {
            sel.selectByVisibleText(opt.getText());
            ok = true;
            break;
          }
        }
        if (!ok) {
          for (WebElement opt : sel.getOptions()) {
            String t = opt.getText() == null ? "" : opt.getText().trim().toLowerCase(Locale.ROOT);
            if (t.contains("finalizada")) {
              sel.selectByVisibleText(opt.getText());
              ok = true;
              break;
            }
          }
        }
        esperarDrupalAjaxTermine();
      }

      // Plazos ONC
      if (plazos == null || plazos.isEmpty()) {
        System.out.println("ℹ Sin plazos para delta " + ofertaDelta);
        return;
      }

      By addMorePlazoOncBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-add-more-add-more-button-plazo-de-presentacion'], " +
          "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-add-more-add-more-button-plazo-de-presentacion']");

      wait.until(ExpectedConditions.presenceOfElementLocated(addMorePlazoOncBy));

      for (int i = 0; i < plazos.size(); i++) {
        PlazoComun p = plazos.get(i);
        if (p == null) continue;

        if (i > 0) asegurarPlazoOncDelta(ofertaDelta, i, addMorePlazoOncBy);
        rellenarPlazoOncDelta(ofertaDelta, i, p.tipoPlazo, p.fechaInicio, p.fechaFin);
      }

      System.out.println("=== ONC delta " + ofertaDelta + " COMPLETADA ===");

    } catch (Exception e) {
      manejarError("Error en procesarOncEnOferta(" + ofertaDelta + ")", e);
    }
  }

  private void asegurarOfertaDelta(int ofertaDelta, By addMoreOfertaBy) {
    int guard = 0;
    while (!existeDeltaOfertas(ofertaDelta)) {
      if (guard++ > 10) throw new RuntimeException("❌ No se creó oferta delta " + ofertaDelta);
      WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(addMoreOfertaBy));
      scrollToCenter(btn);
      safeClick(btn);
      esperarDrupalAjaxTermine();
    }
  }

  private void asegurarPlazoOncDelta(int ofertaDelta, int i, By addMorePlazoOncBy) {
    By deltaWrapperBy = By.cssSelector(
        "div[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "'], " +
        "div[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "--']");

    int guard = 0;
    while (driver.findElements(deltaWrapperBy).isEmpty()) {
      if (guard++ > 10) throw new RuntimeException("❌ No se creó plazo delta " + i + " (oferta " + ofertaDelta + ")");
      WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(addMorePlazoOncBy));
      scrollToCenter(btn);
      safeClick(btn);
      esperarDrupalAjaxTermine();
    }
  }

  private void rellenarPlazoOncDelta(int ofertaDelta, int i, String tipoPlazo, String ini, String fin) {
    By tipoSelectBy = By.cssSelector(
        "select[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "-subform-field-tipo-de-plazo'], " +
        "select[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "-subform-field-tipo-de-plazo']");

    WebElement tipoSelEl = wait.until(ExpectedConditions.presenceOfElementLocated(tipoSelectBy));
    scrollToCenter(tipoSelEl);

    Select sel = new Select(tipoSelEl);
    String deseado = safe(tipoPlazo).trim();

    if (deseado.isEmpty()) {
      seleccionarPrimeraOpcionValida(sel);
    } else {
      if (!selectByVisibleOrContains(sel, deseado)) {
        throw new RuntimeException("❌ Tipo plazo no encontrado: " + deseado);
      }
    }

    ((JavascriptExecutor) driver).executeScript(
        "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));", tipoSelEl);

    esperarDrupalAjaxTermine();

    By iniBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "-subform-field-plazo-presentacion-0-value-date']");
    By finBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-plazos-onc-" + i + "-subform-field-plazo-presentacion-0-end-value-date']");

    WebElement iniEl = wait.until(ExpectedConditions.presenceOfElementLocated(iniBy));
    WebElement finEl = wait.until(ExpectedConditions.presenceOfElementLocated(finBy));

    if (!safe(ini).isBlank()) setDateAndCommit(iniEl, normalizarFecha(ini));
    if (!safe(fin).isBlank()) setDateAndCommit(finEl, normalizarFecha(fin));

    esperarDrupalAjaxTermine();
  }

  private boolean selectByVisibleOrContains(Select sel, String desired) {
    String d = desired.trim().toLowerCase(Locale.ROOT);

    for (WebElement opt : sel.getOptions()) {
      String t = opt.getText() == null ? "" : opt.getText().trim();
      if (!t.isEmpty()
          && !t.toLowerCase(Locale.ROOT).contains("seleccionar")
          && t.equalsIgnoreCase(desired.trim())) {
        sel.selectByVisibleText(opt.getText());
        return true;
      }
    }
    for (WebElement opt : sel.getOptions()) {
      String t = opt.getText() == null ? "" : opt.getText().trim().toLowerCase(Locale.ROOT);
      if (!t.isEmpty() && !t.contains("seleccionar") && t.contains(d)) {
        sel.selectByVisibleText(opt.getText());
        return true;
      }
    }
    return false;
  }

  private void seleccionarPrimeraOpcionValida(Select sel) {
    for (WebElement opt : sel.getOptions()) {
      String t = opt.getText() == null ? "" : opt.getText().trim();
      if (!t.isEmpty() && !t.toLowerCase(Locale.ROOT).contains("seleccionar")) {
        sel.selectByVisibleText(opt.getText());
        return;
      }
    }
  }

  // ---------------------------------------------------------
  // CONVOCATORIA: subida MULTI-FICHERO robusta (solo ONC vacío)
  // ---------------------------------------------------------
  private void subirArchivosConvocatoriaRobusto(List<ArchivoAdjunto> archivos) {

    By addMoreBtnBy = By.cssSelector(
        "div.field-actions input[data-drupal-selector='edit-field-ficheros-convocatorias-add-more-add-more-button-ficheros-adjuntos'], " +
        "div.field-actions input[name='field_ficheros_convocatorias_ficheros_adjuntos_add_more'], " +
        "input[data-drupal-selector='edit-field-ficheros-convocatorias-add-more-add-more-button-ficheros-adjuntos'], " +
        "input[name='field_ficheros_convocatorias_ficheros_adjuntos_add_more']");

    // 1) Filtrar SOLO convocatoria (ONC vacío)
    List<ArchivoAdjunto> conv = new ArrayList<>();
    for (ArchivoAdjunto a : archivos) {
      if (a != null && a.esConvocatoria()) conv.add(a);
    }

    if (conv.isEmpty()) {
      System.out.println("ℹ No hay archivos de convocatoria (ONC vacío) para subir.");
      return;
    }

    // Selector general de inputs file del field
    By fileInputAnyBy = By.cssSelector(
    	    "input[type='file'][data-drupal-selector*='ficheros-convocatorias'], " +
    	    "input[type='file'][name*='ficheros_convocatorias']");

    for (ArchivoAdjunto a : conv) {

      String ruta = normalizarRutaWsl(a.ruta);
      if (ruta == null || ruta.isBlank()) throw new RuntimeException("❌ Ruta vacía/null: " + a.ruta);
      if (!Files.exists(Path.of(ruta))) throw new RuntimeException("❌ No existe el archivo: " + ruta);

      WebElement fileInput = encontrarInputFileConvocatoriaVacioOCrear(addMoreBtnBy, fileInputAnyBy);
      int delta = extraerDeltaConvocatoria(fileInput);

      WebElement root = rootDeltaDesdeInput(fileInput);
      if (root == null) throw new RuntimeException("❌ No se pudo localizar root del delta de convocatoria.");
      scrollToCenter(fileInput);

      fileInput.sendKeys(ruta);

      // Esperar subida: aparece remove o enlace de fichero
      By removeBy = By.cssSelector(
          "input[data-drupal-selector^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fichero-adjunto-0-remove-button'], " +
          "input[id^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fichero-adjunto-0-remove-button']");

      new WebDriverWait(driver, Duration.ofSeconds(90))
          .ignoring(StaleElementReferenceException.class)
          .until(d -> !d.findElements(removeBy).isEmpty()
              || !d.findElements(By.cssSelector("div[data-drupal-selector='edit-field-ficheros-convocatorias-" + delta + "'] span.file a")).isEmpty());

      esperarDrupalAjaxTermine();

      // Root puede quedar stale
      root = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(
          "div[data-drupal-selector='edit-field-ficheros-convocatorias-" + delta + "'], " +
          "div[id^='edit-field-ficheros-convocatorias-" + delta + "']")));

      // Descripción (si existe)
      List<WebElement> descEls = root.findElements(By.cssSelector(
          "input[data-drupal-selector^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fichero-adjunto-0-description'], " +
          "input[id^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fichero-adjunto-0-description']"));
      if (!descEls.isEmpty()) {
        WebElement desc = descEls.get(0);
        scrollToCenter(desc);
        try { desc.clear(); } catch (Exception ignored) {}
        desc.sendKeys(a.nombre == null ? "" : a.nombre);
      }

      // Fecha (si existe)
      List<WebElement> fechaEls = root.findElements(By.cssSelector(
          "input[type='date'][data-drupal-selector^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fecha-adjunto-0-value-date'], " +
          "input[type='date'][id^='edit-field-ficheros-convocatorias-" + delta + "-subform-field-fecha-adjunto-0-value-date']"));
      if (!fechaEls.isEmpty()) {
        setDateAndCommit(fechaEls.get(0), normalizarFecha(a.fechaPub));
      }

      System.out.println("✔ Subido convocatoria delta " + delta + ": " + safe(a.nombre));
      esperarDrupalAjaxTermine();
    }
  }

  private WebElement encontrarInputFileConvocatoriaVacioOCrear(By addMoreBtnBy, By fileInputAnyBy) {

    for (int guard = 0; guard < 12; guard++) {

      List<WebElement> inputs = driver.findElements(fileInputAnyBy);

      // Reutilizar delta vacío
      for (WebElement in : inputs) {
        try {
          if (!in.isDisplayed() || !in.isEnabled()) continue;

          String v = safe(in.getAttribute("value")).trim();
          if (!v.isEmpty()) continue;

          WebElement root = rootDeltaDesdeInput(in);
          if (root == null) continue;

          if (!root.findElements(By.cssSelector("span.file a")).isEmpty()) continue;

          return in;

        } catch (StaleElementReferenceException ignored) {}
      }

      // Crear uno nuevo
      int before = inputs.size();
      WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(addMoreBtnBy));
      scrollToCenter(btn);
      safeClick(btn);
      esperarDrupalAjaxTermine();

      new WebDriverWait(driver, Duration.ofSeconds(30))
          .ignoring(StaleElementReferenceException.class)
          .until(d -> d.findElements(fileInputAnyBy).size() > before);
    }

    throw new RuntimeException("❌ No se encontró/creó un input file vacío para Convocatoria.");
  }

  private WebElement rootDeltaDesdeInput(WebElement fileInput) {
    try {
      return fileInput.findElement(By.xpath(
          "./ancestor::div[starts-with(@data-drupal-selector,'edit-field-ficheros-convocatorias-')][1]"));
    } catch (Exception e) {
      return null;
    }
  }

  private int extraerDeltaConvocatoria(WebElement fileInput) {
    String ds = safe(fileInput.getAttribute("data-drupal-selector"));
    String prefix = "edit-field-ficheros-convocatorias-";
    int p = ds.indexOf(prefix);
    if (p < 0) return -1;
    int start = p + prefix.length();
    int end = ds.indexOf("-subform", start);
    if (end < 0) end = ds.length();
    try {
      return Integer.parseInt(ds.substring(start, end));
    } catch (Exception e) {
      return -1;
    }
  }

  // ---------------------------------------------------------
  // SEGUIMIENTO ONC: subir docs en oferta delta existente
  // ---------------------------------------------------------
  public void subirDocumentacionSeguimientoOncEnOfertaExistente(int ofertaDelta, List<ArchivoAdjunto> archivos) {
    if (archivos == null || archivos.isEmpty()) return;

    // Tab
    By tabOfertasBy = By.cssSelector("a.vertical-tabs__menu-link[href='#edit-group-ofertas-de-necesaria-cober']");
    WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(tabOfertasBy));
    scrollToCenter(tab);
    safeClickAjax(tab);
    esperarDrupalAjaxTermine();

    wait.until(ExpectedConditions.presenceOfElementLocated(By.id("edit-group-ofertas-de-necesaria-cober")));

    if (!existeDeltaOfertas(ofertaDelta)) {
      throw new RuntimeException("La oferta delta " + ofertaDelta + " NO existe. Llama antes a procesarOncEnOferta(" + ofertaDelta + ", ...)");
    }

    List<ArchivoAdjunto> docs = new ArrayList<>();
    for (ArchivoAdjunto a : archivos) if (a != null && !a.esConvocatoria()) docs.add(a);
    if (docs.isEmpty()) return;

    for (ArchivoAdjunto a : docs) {
      String ruta = normalizarRutaWsl(a.ruta);
      if (ruta == null || ruta.isBlank()) continue;
      if (!Files.exists(Path.of(ruta))) continue;

      String baseName = Path.of(ruta).getFileName().toString();
      if (yaExisteFicheroEnPagina(baseName)) continue;

      int deltaSeguimiento = buscarDeltaSeguimientoVacioOCrear(ofertaDelta);
      rellenarSeguimientoOncDelta(ofertaDelta, deltaSeguimiento, a);
    }

    borrarSeguimientosVacios(ofertaDelta);
  }

  private int buscarDeltaSeguimientoVacioOCrear(int ofertaDelta) {
    int existentes = contarSeguimientoDeltas(ofertaDelta);

    for (int i = 0; i < existentes; i++) {
      if (seguimientoDeltaVacio(ofertaDelta, i)) return i;
    }

    By addMoreSeguimientoBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-seguimiento-onc-add-more-add-more-button-documentacion-seguimiento-onc'], " +
        "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta + "-subform-field-seguimiento-onc-add-more-add-more-button-documentacion-seguimiento-onc']");

    WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(addMoreSeguimientoBy));
    scrollToCenter(btn);
    safeClickAjax(btn);
    esperarDrupalAjaxTermine();

    int nuevo = contarSeguimientoDeltas(ofertaDelta) - 1;
    if (nuevo < 0) throw new RuntimeException("No se creó seguimiento ONC nuevo.");
    return nuevo;
  }

  private int contarSeguimientoDeltas(int ofertaDelta) {
    int i = 0;
    for (; i < 50; i++) {
      By root = By.cssSelector("div[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
          "-subform-field-seguimiento-onc-" + i + "']");
      if (driver.findElements(root).isEmpty()) break;
    }
    return i;
  }

  private boolean seguimientoDeltaVacio(int ofertaDelta, int delta) {
    By rootBy = By.cssSelector("div[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "']");
    List<WebElement> roots = driver.findElements(rootBy);
    if (roots.isEmpty()) return true;

    WebElement root = roots.get(0);
    return root.findElements(By.cssSelector("span.file a")).isEmpty();
  }

  private void rellenarSeguimientoOncDelta(int ofertaDelta, int delta, ArchivoAdjunto a) {
    // Abrir párrafo
    By tituloParagraphBy = By.cssSelector(
        "div.paragraph-type-title[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-top-paragraph-type-title'], " +
        "div.paragraph-type-title[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-top-paragraph-type-title']");

    WebElement tituloParagraph = wait.until(ExpectedConditions.presenceOfElementLocated(tituloParagraphBy));
    scrollToCenter(tituloParagraph);
    safeClickAjax(tituloParagraph);
    esperarDrupalAjaxTermine();

    // Activar "título libre"
    By chkBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-titulo-libre-value']");

    WebElement chk = wait.until(ExpectedConditions.presenceOfElementLocated(chkBy));
    if (!chk.isSelected()) {
      scrollToCenter(chk);
      safeClickAjax(chk);
      ((JavascriptExecutor) driver).executeScript(
          "arguments[0].checked = true;" +
          "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
          "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));" +
          "arguments[0].dispatchEvent(new Event('blur', {bubbles:true}));", chk);
    }

    // Título input
    By tituloInputBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-titulo-0-value']");

    WebElement tituloInput = wait.until(ExpectedConditions.presenceOfElementLocated(tituloInputBy));
    setTextAndCommit(tituloInput, safe(a.nombre));
    esperarDrupalAjaxTermine();

    // Asegurar adjunto 0
    By fileUploadBy = By.cssSelector(
        "input[type='file'][data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-upload'], " +
        "input[type='file'][id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-upload']");

    if (driver.findElements(fileUploadBy).isEmpty()) {
      By addMoreAdjuntoBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
          "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-add-more-add-more-button-ficheros-adjuntos'], " +
          "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
          "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-add-more-add-more-button-ficheros-adjuntos']");

      WebElement addAdj = wait.until(ExpectedConditions.elementToBeClickable(addMoreAdjuntoBy));
      scrollToCenter(addAdj);
      safeClickAjax(addAdj);
      esperarDrupalAjaxTermine();
    }

    WebElement fileInput = wait.until(ExpectedConditions.presenceOfElementLocated(fileUploadBy));
    scrollToCenter(fileInput);
    fileInput.sendKeys(normalizarRutaWsl(a.ruta));

    // Esperar a que exista remove (subida completada)
    By removeBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-remove-button'], " +
        "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-remove-button']");

    new WebDriverWait(driver, Duration.ofSeconds(90))
        .ignoring(StaleElementReferenceException.class)
        .until(d -> !d.findElements(removeBy).isEmpty());

    // Descripción
    By descBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-description'], " +
        "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fichero-adjunto-0-description']");

    List<WebElement> descEls = driver.findElements(descBy);
    if (!descEls.isEmpty()) {
      WebElement desc = wait.until(ExpectedConditions.elementToBeClickable(descBy));
      desc.clear();
      desc.sendKeys(safe(a.nombre));
    }

    // Fecha adjunto (si existe)
    By fechaBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fecha-adjunto-0-value-date'], " +
        "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
        "-subform-field-seguimiento-onc-" + delta + "-subform-field-adjuntos-0-subform-field-fecha-adjunto-0-value-date']");

    List<WebElement> fechaEls = driver.findElements(fechaBy);
    if (!fechaEls.isEmpty()) {
      String f = safe(a.fechaPub).isBlank()
          ? LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
          : normalizarFecha(a.fechaPub);
      setDateAndCommit(fechaEls.get(0), f);
    }

    safeClickAjax(tituloInput);
    esperarDrupalAjaxTermine();
  }

  private void borrarSeguimientosVacios(int ofertaDelta) {
    int total = contarSeguimientoDeltas(ofertaDelta);
    for (int i = total - 1; i >= 0; i--) {
      if (!seguimientoDeltaVacio(ofertaDelta, i)) continue;

      By borrarBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
          "-subform-field-seguimiento-onc-" + i + "-top-links-remove-button'], " +
          "input[id^='edit-field-ofertas-de-necesaria-cober-" + ofertaDelta +
          "-subform-field-seguimiento-onc-" + i + "-top-links-remove-button']");

      List<WebElement> botones = driver.findElements(borrarBy);
      if (botones.isEmpty()) continue;

      WebElement btn = botones.get(0);
      scrollToCenter(btn);
      safeClickAjax(btn);
      esperarDrupalAjaxTermine();
    }
  }

  // Detección simple de duplicados por nombre de fichero (en toda la página)
  private boolean yaExisteFicheroEnPagina(String baseName) {
    if (baseName == null || baseName.isBlank()) return false;
    String lit = xpathLiteral(baseName.trim());
    By by = By.xpath("//span[contains(@class,'file')]/a[contains(normalize-space(.), " + lit + ")]");
    return !driver.findElements(by).isEmpty();
  }

  /**
   * Literal XPath seguro:
   * - 'texto' si no hay comillas simples
   * - "texto" si no hay comillas dobles
   * - concat('a', "'", 'b', "'", 'c') si hay ambas
   */
  private static String xpathLiteral(String s) {
    if (s == null) return "''";
    if (!s.contains("'")) return "'" + s + "'";
    if (!s.contains("\"")) return "\"" + s + "\"";

    String[] parts = s.split("'");
    StringBuilder sb = new StringBuilder("concat(");
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) sb.append(", \"'\", ");
      sb.append("'").append(parts[i]).append("'");
    }
    sb.append(")");
    return sb.toString();
  }

  // ---------------------------------------------------------
  // TAB "PLAZOS" (fijo)
  // ---------------------------------------------------------
  public void rellenarPlazos(String fechaInicio, String fechaFin, String tipoPlazo) {
    try {
      By tabPlazosBy = By.cssSelector("a.vertical-tabs__menu-link[href='#edit-group-plazos']");
      WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(tabPlazosBy));
      scrollToCenter(tab);
      safeClickAjax(tab);
      esperarDrupalAjaxTermine();

      wait.until(ExpectedConditions.presenceOfElementLocated(By.id("edit-group-plazos")));

      By addMorePlazoBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-plazos-de-presentacion-add-more-add-more-button-plazo-de-presentacion'], " +
          "input[id^='edit-field-plazos-de-presentacion-add-more-add-more-button-plazo-de-presentacion']");

      wait.until(ExpectedConditions.presenceOfElementLocated(addMorePlazoBy));

      int delta = buscarDeltaPlazosVacioOCrear(addMorePlazoBy);

      By tipoBy = By.cssSelector(
          "select[data-drupal-selector='edit-field-plazos-de-presentacion-" + delta + "-subform-field-tipo-de-plazo'], " +
          "select[id^='edit-field-plazos-de-presentacion-" + delta + "-subform-field-tipo-de-plazo']");

      WebElement tipoSelEl = wait.until(ExpectedConditions.presenceOfElementLocated(tipoBy));
      scrollToCenter(tipoSelEl);

      Select sel = new Select(tipoSelEl);
      String deseado = safe(tipoPlazo).trim();

      if (deseado.isEmpty()) seleccionarPrimeraOpcionValida(sel);
      else if (!selectByVisibleOrContains(sel, deseado)) throw new RuntimeException("❌ Tipo plazo no encontrado: " + deseado);

      ((JavascriptExecutor) driver).executeScript(
          "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));", tipoSelEl);

      esperarDrupalAjaxTermine();

      By iniBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-plazos-de-presentacion-" + delta + "-subform-field-plazo-presentacion-0-value-date']");
      By finBy = By.cssSelector(
          "input[data-drupal-selector='edit-field-plazos-de-presentacion-" + delta + "-subform-field-plazo-presentacion-0-end-value-date']");

      WebElement iniEl = wait.until(ExpectedConditions.presenceOfElementLocated(iniBy));
      WebElement finEl = wait.until(ExpectedConditions.presenceOfElementLocated(finBy));

      if (!safe(fechaInicio).isBlank()) setDateAndCommit(iniEl, normalizarFecha(fechaInicio));
      if (!safe(fechaFin).isBlank()) setDateAndCommit(finEl, normalizarFecha(fechaFin));

      esperarDrupalAjaxTermine();

      System.out.println("✔ Plazos (tab Plazos) rellenado en delta " + delta);

    } catch (Exception e) {
      manejarError("Error en rellenarPlazos()", e);
    }
  }

  private int buscarDeltaPlazosVacioOCrear(By addMorePlazoBy) {
    int existentes = contarPlazosDeltas();

    for (int i = 0; i < existentes; i++) {
      if (plazoDeltaVacio(i)) return i;
    }

    WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(addMorePlazoBy));
    scrollToCenter(btn);
    safeClickAjax(btn);
    esperarDrupalAjaxTermine();

    int nuevo = contarPlazosDeltas() - 1;
    if (nuevo < 0) throw new RuntimeException("❌ No se creó plazo nuevo.");
    return nuevo;
  }

  private int contarPlazosDeltas() {
    int i = 0;
    for (; i < 50; i++) {
      By root = By.cssSelector("div[data-drupal-selector='edit-field-plazos-de-presentacion-" + i + "']");
      if (driver.findElements(root).isEmpty()) break;
    }
    return i;
  }

  private boolean plazoDeltaVacio(int delta) {
    By iniBy = By.cssSelector(
        "input[data-drupal-selector='edit-field-plazos-de-presentacion-" + delta + "-subform-field-plazo-presentacion-0-value-date']");
    By tipoBy = By.cssSelector(
        "select[data-drupal-selector='edit-field-plazos-de-presentacion-" + delta + "-subform-field-tipo-de-plazo']");

    List<WebElement> iniEls = driver.findElements(iniBy);
    List<WebElement> tipoEls = driver.findElements(tipoBy);

    String iniVal = iniEls.isEmpty() ? "" : safe(iniEls.get(0).getAttribute("value")).trim();
    String tipoVal = tipoEls.isEmpty() ? "_none" : safe(tipoEls.get(0).getAttribute("value")).trim();

    return iniVal.isEmpty() && (tipoVal.isEmpty() || "_none".equals(tipoVal));
  }

  // ---------------------------------------------------------
  // AJAX + setters + errores
  // ---------------------------------------------------------
  private void esperarDrupalAjaxTermine() {
    new WebDriverWait(driver, Duration.ofSeconds(60))
        .ignoring(StaleElementReferenceException.class)
        .until(d -> d.findElements(By.cssSelector(".ajax-progress, .ajax-throbber")).isEmpty());
  }

  private void setDateAndCommit(WebElement fechaInput, String fechaNormalizada) {
    scrollToCenter(fechaInput);
    ((JavascriptExecutor) driver).executeScript(
        "arguments[0].value = arguments[1];" +
            "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
            "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));" +
            "arguments[0].dispatchEvent(new Event('blur', {bubbles:true}));",
        fechaInput, fechaNormalizada);
  }

  private void setTextAndCommit(WebElement input, String value) {
    scrollToCenter(input);
    ((JavascriptExecutor) driver).executeScript(
        "arguments[0].value = arguments[1];" +
            "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
            "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));" +
            "arguments[0].dispatchEvent(new Event('blur', {bubbles:true}));",
        input, value == null ? "" : value);
  }

  private void manejarError(String mensaje, Exception e) {
    System.out.println("====================================");
    System.out.println("❌ " + mensaje);
    System.out.println("Tipo error: " + e.getClass().getName());
    System.out.println("Mensaje: " + e.getMessage());
    System.out.println("====================================");
    e.printStackTrace();

    if (driver != null) {
      try {
        TakesScreenshot ts = (TakesScreenshot) driver;
        File src = ts.getScreenshotAs(OutputType.FILE);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path destino = Paths.get("error_" + timestamp + ".png");
        Files.copy(src.toPath(), destino, StandardCopyOption.REPLACE_EXISTING);

        System.out.println(" Screenshot guardado en: " + destino.toAbsolutePath());
      } catch (IOException io) {
        System.out.println("No se pudo guardar screenshot.");
      }
    }
  }

  // ---------------------------------------------------------
  // Helpers DOM
  // ---------------------------------------------------------
  private void safeClick(WebElement el) {
    try { el.click(); }
    catch (Exception ex) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el); }
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

  private void scrollToCenter(WebElement el) {
    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", el);
  }

  private boolean existeDeltaOfertas(int i) {
    try { return !driver.findElements(ofertasRowBy(i)).isEmpty(); }
    catch (Exception e) { return false; }
  }

  private By ofertasRowBy(int i) {
    return By.cssSelector("[id^='edit-field-ofertas-de-necesaria-cober-" + i + "-subform']");
  }



private static final boolean IS_WINDOWS =
    System.getProperty("os.name").toLowerCase().contains("win");

private static String normalizarRutaWsl(String ruta) {
  if (ruta == null) return null;

  String r = ruta.trim().replace('\u00A0', ' ');
  if (r.isEmpty()) return r;

  // Unificar separadores para detectar patrones con facilidad
  r = r.replace('\\', '/');

  // 1) Si estoy en WINDOWS y me llega una ruta WSL: /mnt/d/loquesea -> D:\loquesea
  if (IS_WINDOWS && r.matches("^/mnt/[A-Za-z]/.*")) {
    char drive = Character.toUpperCase(r.charAt(5)); // /mnt/d/...
    String rest = r.substring(6);                    // después de /mnt/d
    if (rest.startsWith("/")) rest = rest.substring(1);

    String win = drive + ":\\" + rest.replace('/', '\\');

    // Aquí sí podemos usar Paths porque ya es una ruta windows válida
    try { return Paths.get(win).normalize().toString(); }
    catch (Exception e) { return win; }
  }

  // 2) Si estoy en LINUX/WSL y me llega una ruta Windows: D:\a\b o D:/a/b -> /mnt/d/a/b
  if (!IS_WINDOWS && r.matches("^[A-Za-z]:/.*")) {
    char drive = Character.toLowerCase(r.charAt(0));
    String rest = r.substring(2);
    if (!rest.startsWith("/")) rest = "/" + rest;
    return normalizeUnix("/mnt/" + drive + rest);
  }

  // 3) Si ya viene en formato "unix" (o relativo), lo normalizo como unix.
  //    En Windows NO uses Paths.get(...) para esto, porque convertirá /mnt/d/... en \mnt\d\...
  if (!IS_WINDOWS) {
    // En Linux/WSL, Paths funciona bien con unix
    try { return Paths.get(r).normalize().toString().replace('\\', '/'); }
    catch (Exception e) { return normalizeUnix(r); }
  } else {
    // En Windows: si no era /mnt/... ni drive:\..., devolvemos algo razonable:
    // - si era una ruta relativa o tipo "/algo", la dejamos con separadores Windows
    String winish = r.replace('/', '\\');
    try { return Paths.get(winish).normalize().toString(); }
    catch (Exception e) { return winish; }
  }
}

/** Normaliza "." y ".." en rutas con "/" (estilo Unix) sin depender del SO. */
private static String normalizeUnix(String path) {
  boolean abs = path.startsWith("/");
  String[] parts = path.split("/+");
  Deque<String> stack = new ArrayDeque<>();

  for (String p : parts) {
    if (p.isEmpty() || ".".equals(p)) continue;
    if ("..".equals(p)) {
      if (!stack.isEmpty() && !"..".equals(stack.peekLast())) stack.removeLast();
      else if (!abs) stack.addLast("..");
    } else {
      stack.addLast(p);
    }
  }

  StringBuilder sb = new StringBuilder();
  if (abs) sb.append("/");
  sb.append(String.join("/", stack));
  return sb.toString();
}

  
  private static String normalizarFecha(String fechaEntrada) {
    if (fechaEntrada == null || fechaEntrada.trim().isEmpty()) {
      throw new RuntimeException("❌ La fecha viene vacía o null");
    }

    fechaEntrada = fechaEntrada.trim();
    DateTimeFormatter salida = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    DateTimeFormatter[] formatosEntrada = new DateTimeFormatter[] {
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yy"),
        DateTimeFormatter.ofPattern("dd-MM-yy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yy")
    };

    for (DateTimeFormatter formato : formatosEntrada) {
      try {
        LocalDate fecha = LocalDate.parse(fechaEntrada, formato);
        return fecha.format(salida);
      } catch (DateTimeParseException ignored) {}
    }

    throw new RuntimeException("❌ Formato de fecha no válido: " + fechaEntrada);
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }


  // ---------------------------------------------------------
// GUARDAR + PUBLICAR + LOG ERRORES + SCREENSHOT
// ---------------------------------------------------------
public boolean publicarYGuardarYEsperar(String organismo) {
  String org = safe(organismo).isBlank() ? "(sin organismo)" : organismo.trim();

  try {
    By publicadoBy = By.cssSelector("input#edit-status-value[data-drupal-selector='edit-status-value']");
    By guardarBy = By.cssSelector("input#gin-sticky-edit-submit[data-drupal-selector='gin-sticky-edit-submit']");

    // Mensajes Drupal Gin
    By errorMsgBy = By.cssSelector(".messages__wrapper .messages--error");
    By okMsgBy = By.cssSelector(".messages__wrapper .messages--status, .messages__wrapper .messages--success");

    // Asegurar que los botones están presentes
    WebElement chkPublicado = wait.until(ExpectedConditions.presenceOfElementLocated(publicadoBy));
    WebElement btnGuardar = wait.until(ExpectedConditions.elementToBeClickable(guardarBy));

    // Marcar Publicado si no lo está
    if (!chkPublicado.isSelected()) {
      scrollToCenter(chkPublicado);
      safeClickAjax(chkPublicado);

      // asegurar que queda marcado (a veces click no “entra” por overlays)
      new WebDriverWait(driver, Duration.ofSeconds(10))
          .ignoring(StaleElementReferenceException.class)
          .until(d -> {
            try {
              WebElement c = d.findElement(publicadoBy);
              return c.isSelected();
            } catch (Exception e) { return false; }
          });
    }

    // Guardar: antes limpiamos mensajes anteriores si existían (opcional)
    // (No “cerramos” porque puede no existir el botón de ocultar)
    // Si quieres, lo puedo añadir.

    String urlAntes = driver.getCurrentUrl();

    scrollToCenter(btnGuardar);
    safeClickAjax(btnGuardar);

    // Espera a que:
    // - aparezca error, o
    // - aparezca ok, o
    // - cambie url (fallback)
    WebDriverWait waitSave = new WebDriverWait(driver, Duration.ofSeconds(90));
    waitSave.ignoring(StaleElementReferenceException.class).until(d -> {
      if (!d.findElements(errorMsgBy).isEmpty()) return true;
      if (!d.findElements(okMsgBy).isEmpty()) return true;
      String now = "";
      try { now = d.getCurrentUrl(); } catch (Exception ignored) {}
      return now != null && !now.equals(urlAntes);
    });

    esperarDrupalAjaxTermine();

    // Si hay error -> log + screenshot y devolver false
    List<WebElement> errores = driver.findElements(errorMsgBy);
    if (!errores.isEmpty()) {
      String txt = errores.get(0).getText();
      registrarErrorGuardado(org, txt);
      guardarScreenshot("validation_error_" + limpiarNombre(org));
      return false;
    }

    // Si no hay error, OK (aunque no veamos status, igual guardó)
    Thread.sleep(2000); // requisito: esperar 2s antes de cambiar de organismo
    return true;

  } catch (Exception e) {
    // Excepción real (timeout, stale, etc.) -> también log + screenshot
    registrarErrorGuardado(safe(organismo), "EXCEPCION: " + e.getClass().getName() + " - " + e.getMessage());
    guardarScreenshot("exception_" + limpiarNombre(safe(organismo)));
    // si quieres, además reutiliza tu manejarError:
    // manejarError("Error en publicarYGuardarYEsperar()", e);
    return false;
  }
}

private void registrarErrorGuardado(String organismo, String detalle) {
  try {
    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    String bloque =
        "====================================================\n" +
        "FECHA: " + ts + "\n" +
        "ORGANISMO: " + safe(organismo) + "\n" +
        "DETALLE:\n" + safe(detalle) + "\n";

    Path log = Paths.get("errores_guardado_cap.txt");
    Files.writeString(
        log,
        bloque,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
    );

    System.out.println("❌ Error de guardado registrado en: " + log.toAbsolutePath());
  } catch (Exception ex) {
    System.out.println("⚠ No se pudo escribir el log de errores: " + ex.getMessage());
  }
}

private void guardarScreenshot(String prefix) {
  if (driver == null) return;
  try {
    TakesScreenshot ts = (TakesScreenshot) driver;
    File src = ts.getScreenshotAs(OutputType.FILE);

    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    String name = (safe(prefix).isBlank() ? "error" : prefix) + "_" + timestamp + ".png";
    Path destino = Paths.get(name);

    Files.copy(src.toPath(), destino, StandardCopyOption.REPLACE_EXISTING);
    System.out.println("📸 Screenshot guardado: " + destino.toAbsolutePath());
  } catch (Exception e) {
    System.out.println("⚠ No se pudo guardar screenshot: " + e.getMessage());
  }
}

private static String limpiarNombre(String s) {
  String x = safe(s).trim();
  if (x.isEmpty()) return "sin_nombre";
  // para nombre de fichero
  x = x.replaceAll("[^a-zA-Z0-9._-]+", "_");
  if (x.length() > 80) x = x.substring(0, 80);
  return x;
}
}