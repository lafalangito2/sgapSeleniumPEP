package ods;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.odftoolkit.simple.SpreadsheetDocument;
import org.odftoolkit.simple.table.Row;
import org.odftoolkit.simple.table.Table;

import selenium.testAPPsegOK;

/**
 * Lee un ODS y ejecuta el mismo flujo que el main() de testAPPsegOK,
 * pero soportando ONC en 3 grupos:
 * - Convocatoria  (ONC vacio)
 * - 01_onc        (ONC1 => oferta delta 0)
 * - 02_onc        (ONC2 => oferta delta 1)  -> requiere crear nueva ONC
 *
 * Ademas:
 * - Plazos ONC1 se toman de hoja "PLAZOS COMUNES"
 * - Plazos ONC2 se toman de hoja "PLAZOS" filtrando por organismo (y por ONC=02_onc si existe esa columna)
 */
public class OdsToSelenium {

  private static final String RUTA_PREFIX = "D:\\Descargas\\";
  private static final String SHEET_PLAZOS_COMUNES = "PLAZOS COMUNES";
  private static final String SHEET_PLAZOS = "PLAZOS";

  // Evita cuelgues del header (ODS con used-range enorme)
  private static final int HEADER_MAX_COLS = 800;
  private static final int HEADER_MAX_EMPTY_STREAK = 40;
  private static final boolean IS_WINDOWS = false;

  public static void main(String[] args) {
    if (args.length == 0 || args[0] == null || args[0].trim().isEmpty()) {
      System.err.println("Debes indicar la ruta del archivo ODS.");
      System.exit(1);
    }

    String odsPath = args[0].trim();
    System.out.println("=== INICIO PROCESO (ONC01 + ONC02) ===");
    System.out.println("ODS: " + odsPath);

    try {
      File file = new File(odsPath);
      if (!file.exists() || !file.isFile()) {
        throw new RuntimeException("No existe el ODS: " + odsPath);
      }

      SpreadsheetDocument document = SpreadsheetDocument.loadDocument(file);

      // Hoja principal (index 0)
//      Table sheet0 = document.getSheetByIndex(0);

      Table sheet0 = document.getSheetByName("CAP_2025");
      // Hoja PLAZOS COMUNES (ONC1)
      Table sheetPlazosComunes = findSheetByName(document, SHEET_PLAZOS_COMUNES);
      List<PlazoComun> plazosComunes = (sheetPlazosComunes == null)
          ? new ArrayList<>()
          : readPlazosComunes(sheetPlazosComunes);

      System.out.println("PLAZOS COMUNES leidos: " + plazosComunes.size());

      // Hoja PLAZOS (ONC2 por organismo)
      Table sheetPlazos = findSheetByName(document, SHEET_PLAZOS);
      Map<String, List<PlazoComun>> plazosOnc2PorOrg = (sheetPlazos == null)
          ? new HashMap<>()
          : readPlazosOnc2PorOrganismo(sheetPlazos);

      System.out.println("PLAZOS (ONC2) organismos con datos: " + plazosOnc2PorOrg.size());

      // Header indices (hoja principal)
      Map<String, Integer> col = headerIndexFast(sheet0);

      int IDX_ORG       = requireIndex(col, "Organismo convocante");
      int IDX_ONC       = requireIndex(col, "ONC");
      int IDX_FECHA_PUB = requireIndex(col, "Fecha pub");
      int IDX_FICHERO   = requireIndex(col, "fichero");
      int IDX_NORMDOC   = requireIndex(col, "Nombre normalizado del documento");

      int IDX_PART      = col.getOrDefault(norm("Nombre particular"), -1); // opcional
      int IDX_LEGI      = col.getOrDefault(norm("Legislatura II"), -1);    // opcional

      int rows0 = sheet0.getRowCount();

      // =======================================================
      // AGRUPAR POR ORGANISMO (y separar convocatoria/onc01/onc02)
      // =======================================================
      Map<String, OrganismoPack> packs = new LinkedHashMap<>(256);

      for (int r = 1; r < rows0; r++) {
        Row row = sheet0.getRowByIndex(r);

        String org = cell(row, IDX_ORG);
        if (isBlank(org)) continue;

        OrganismoPack p = packs.computeIfAbsent(org, OrganismoPack::new);

        if (p.descripcion == null) {
          p.descripcion = "Concurso abierto y permanente de funcionarios del organismo " + org;
        }

        if (p.organismoBusqueda == null || p.organismoBusqueda.isBlank()) {
          String legi = (IDX_LEGI >= 0) ? cell(row, IDX_LEGI) : "";
          p.organismoBusqueda = !isBlank(legi) ? legi : org; // fallback
        }

        String oncCode = normalizarOncCode(cell(row, IDX_ONC)); // "", "01_onc", "02_onc"
        String fechaPub = cell(row, IDX_FECHA_PUB);

        String fichero = cell(row, IDX_FICHERO);
        if (isBlank(fichero)) continue;

        String ruta = buildFullPath(fichero.trim());

        String nombreParticular = (IDX_PART >= 0) ? cell(row, IDX_PART) : "";
        String nombreNormalizado = cell(row, IDX_NORMDOC);
        String nombre = !isBlank(nombreParticular) ? nombreParticular : nombreNormalizado;

        ArchivoAdjunto adj = new ArchivoAdjunto(ruta, nombre, fechaPub, oncCode);

        if (adj.esConvocatoria()) {
          p.convocatoria.add(adj);
        } else if (adj.esOnc01()) {
          p.onc01.add(adj);
          p.minFechaOnc01 = minFecha(p.minFechaOnc01, fechaPub);
        } else if (adj.esOnc02()) {
          p.onc02.add(adj);
          p.minFechaOnc02 = minFecha(p.minFechaOnc02, fechaPub);
        } else {
          // cualquier otro valor => lo tratamos como convocatoria por seguridad
          p.convocatoria.add(adj);
        }
      }

      // Adjuntar plazos ONC2 por organismo
      for (OrganismoPack p : packs.values()) {
        p.plazosOnc01 = plazosComunes;
        p.plazosOnc02 = plazosOnc2PorOrg.getOrDefault(p.organismo, Collections.emptyList());

        p.convocatoria = dedup(p.convocatoria);
        p.onc01 = dedup(p.onc01);
        p.onc02 = dedup(p.onc02);
      }

      System.out.println("Organismos detectados: " + packs.size());

      // =======================================================
      // EJECUCION SELENIUM POR ORGANISMO
      // =======================================================
      for (OrganismoPack p : packs.values()) {

        boolean hayAlgo = !p.convocatoria.isEmpty()
            || !p.onc01.isEmpty()
            || !p.onc02.isEmpty()
            || (p.plazosOnc02 != null && !p.plazosOnc02.isEmpty());

        if (!hayAlgo) continue;

        System.out.println("\n============================================");
        System.out.println("ORG: " + p.organismo);
        System.out.println("Busqueda select2: " + p.organismoBusqueda);
        System.out.println("Convocatoria: " + p.convocatoria.size());
        System.out.println("ONC01: " + p.onc01.size() + " | minFecha=" + safe(p.minFechaOnc01));
        System.out.println("ONC02: " + p.onc02.size() + " | minFecha=" + safe(p.minFechaOnc02));
        System.out.println("Plazos ONC01: " + (p.plazosOnc01 == null ? 0 : p.plazosOnc01.size()));
        System.out.println("Plazos ONC02: " + (p.plazosOnc02 == null ? 0 : p.plazosOnc02.size()));
        System.out.println("============================================");

        testAPPsegOK app = new testAPPsegOK();
        try {
          // CAP: pasamos todo, Selenium sube SOLO convocatoria
          List<ArchivoAdjunto> todos = new ArrayList<>();
          todos.addAll(p.convocatoria);
          todos.addAll(p.onc01);
          todos.addAll(p.onc02);

          app.rellenarFormularioCAP(p.descripcion, p.organismoBusqueda, todos);

          // ===== ONC01 (delta 0)
          app.plazoscomunes(p.organismo, p.plazosOnc01, p.minFechaOnc01);

          if (!p.onc01.isEmpty()) {
            app.subirDocumentacionSeguimientoOncEnOfertaExistente(0, p.onc01);
          }

          // ===== ONC02 (delta 1) => crear nueva ONC si hace falta
          boolean hayOnc2 = !p.onc02.isEmpty()
              || (p.plazosOnc02 != null && !p.plazosOnc02.isEmpty())
              || !isBlank(p.minFechaOnc02);

          if (hayOnc2) {
            app.procesarOncEnOferta(1, p.plazosOnc02, p.minFechaOnc02);

            if (!p.onc02.isEmpty()) {
              app.subirDocumentacionSeguimientoOncEnOfertaExistente(1, p.onc02);
            }
          }

          // ===== Fijo (como en tu main de testAPPsegOK)
          app.rellenarPlazos("09/06/25", "23/06/25", "Presentación solicitudes CAP");

      boolean ok = app.publicarYGuardarYEsperar(p.organismo);
  
      if (!ok) {
    System.out.println("⚠ CAP NO guardado por errores de validación. Organismo: " + p.organismo);

}

          
        } finally {
          app.close();
        }
      }

      System.out.println("\n=== FIN PROCESO ===");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }






  
  // ===================== LECTURAS =====================

  private static List<PlazoComun> readPlazosComunes(Table sheetPlazos) {
    Map<String, Integer> col = headerIndexFast(sheetPlazos);

    int IDX_TIPO = requireIndex(col, "TIPO DE PLAZO");
    int IDX_INI  = requireIndex(col, "Fecha inicio");
    int IDX_FIN  = requireIndex(col, "Fecha fin");

    List<PlazoComun> out = new ArrayList<>();
    int rows = sheetPlazos.getRowCount();

    for (int r = 1; r < rows; r++) {
      Row row = sheetPlazos.getRowByIndex(r);

      String tipo = cell(row, IDX_TIPO);
      String ini  = cell(row, IDX_INI);
      String fin  = cell(row, IDX_FIN);

      if (isBlank(tipo) && isBlank(ini) && isBlank(fin)) continue;
      out.add(new PlazoComun(tipo, ini, fin));
    }
    return out;
  }

  /**
   * Hoja PLAZOS: recupera plazos para ONC2 por organismo.
   * Si existe columna "ONC", filtra por "02_onc". Si no, asume que todas las filas son ONC2.
   */
  private static Map<String, List<PlazoComun>> readPlazosOnc2PorOrganismo(Table sheetPlazos) {
    Map<String, Integer> col = headerIndexFast(sheetPlazos);

    int IDX_ORG  = requireIndex(col, "organismo");
    int IDX_TIPO = requireIndex(col, "Tipo de plazo");
    int IDX_INI  = requireIndex(col, "Fecha inicio");
    int IDX_FIN  = requireIndex(col, "Fecha fin");

    Integer IDX_ONC = col.get(norm("ONC")); // opcional

    Map<String, List<PlazoComun>> out = new HashMap<>(256);
    int rows = sheetPlazos.getRowCount();

    for (int r = 1; r < rows; r++) {
      Row row = sheetPlazos.getRowByIndex(r);

      String org = cell(row, IDX_ORG);
      if (isBlank(org)) continue;

      if (IDX_ONC != null) {
        String onc = normalizarOncCode(cell(row, IDX_ONC));
        if (!"02_onc".equalsIgnoreCase(onc)) continue;
      }

      String tipo = cell(row, IDX_TIPO);
      String ini  = cell(row, IDX_INI);
      String fin  = cell(row, IDX_FIN);

      if (isBlank(tipo) && isBlank(ini) && isBlank(fin)) continue;

      out.computeIfAbsent(org, k -> new ArrayList<>()).add(new PlazoComun(tipo, ini, fin));
    }

    return out;
  }

  // ===================== ONC NORMALIZACION =====================

  private static String normalizarOncCode(String s) {
    if (s == null) return "";
    String t = s.trim().toLowerCase(Locale.ROOT);
    if (t.isEmpty()) return "";
    if (t.contains("02") && t.contains("onc")) return "02_onc";
    if (t.contains("01") && t.contains("onc")) return "01_onc";
    if ("02_onc".equals(t)) return "02_onc";
    if ("01_onc".equals(t)) return "01_onc";
    return t;
  }

  // ===================== FECHAS =====================

  private static String minFecha(String actual, String candidata) {
    if (isBlank(actual)) return candidata;
    if (isBlank(candidata)) return actual;

    LocalDate da = tryParse(actual);
    LocalDate dc = tryParse(candidata);

    if (da != null && dc != null) {
      return dc.isBefore(da) ? candidata : actual;
    }
    return actual;
  }

  private static LocalDate tryParse(String s) {
    if (s == null) return null;
    DateTimeFormatter[] f = {
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/yy"),
        DateTimeFormatter.ofPattern("d/M/yy")
    };
    for (DateTimeFormatter ff : f) {
      try {
        return LocalDate.parse(s.trim(), ff);
      } catch (DateTimeParseException ignore) {}
    }
    return null;
  }

  // ===================== HELPERS (ODS) =====================

  private static Map<String, Integer> headerIndexFast(Table sheet) {
    Row header = sheet.getRowByIndex(0);
    Map<String, Integer> m = new HashMap<>(64);

    int emptyStreak = 0;
    for (int c = 0; c < HEADER_MAX_COLS; c++) {
      String h = header.getCellByIndex(c).getDisplayText();
      h = (h == null) ? "" : h.trim();

      if (h.isEmpty()) {
        emptyStreak++;
        if (emptyStreak >= HEADER_MAX_EMPTY_STREAK && !m.isEmpty()) break;
        continue;
      }

      emptyStreak = 0;
      m.put(norm(h), c);
    }
    return m;
  }

  private static int requireIndex(Map<String, Integer> col, String name) {
    Integer idx = col.get(norm(name));
    if (idx == null) throw new IllegalArgumentException("Falta columna: " + name);
    return idx;
  }

  private static String cell(Row row, int idx) {
    String t = row.getCellByIndex(idx).getDisplayText();
    return t == null ? "" : t.trim();
  }

  private static String norm(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static Table findSheetByName(SpreadsheetDocument doc, String name) {
    for (int i = 0; i < doc.getSheetCount(); i++) {
      Table t = doc.getSheetByIndex(i);
      if (name.equals(t.getTableName())) return t;
    }
    return null;
  }

private static String buildFullPath(String ficheroNormalizado) {
    if (isBlank(ficheroNormalizado)) return RUTA_PREFIX;

    String f = ficheroNormalizado.trim();

    // Windows absoluto con letra: D:\... o D:/...
    if (f.matches("^[A-Za-z]:[\\/].*")) {
        return normalizeSeparators(f);
    }

    // UNC: \servidor\share\...
    if (f.startsWith("\\") || f.startsWith("//")) {
        return normalizeSeparators(f);
    }

    // Construcción segura y normalizada
    Path base = Paths.get(RUTA_PREFIX);
    Path full = base.resolve(f.replace("/", File.separator)).normalize();
    return full.toString();
}

private static String normalizeSeparators(String path) {
    return IS_WINDOWS ? path.replace("/", "\\") : path.replace("\\", "/");
}

  private static List<ArchivoAdjunto> dedup(List<ArchivoAdjunto> in) {
    Map<String, ArchivoAdjunto> map = new LinkedHashMap<>();
    for (ArchivoAdjunto a : in) {
      if (a == null) continue;
      String k = safe(a.ruta) + "|" + safe(a.nombre) + "|" + safe(a.fechaPub) + "|" + safe(a.oncCode);
      map.putIfAbsent(k, a);
    }
    return new ArrayList<>(map.values());
  }

  // ===================== MODELOS =====================

  private static class OrganismoPack {
    final String organismo;
    String organismoBusqueda;  // texto que se teclea en el select2 del formulario
    String descripcion;

    List<ArchivoAdjunto> convocatoria = new ArrayList<>(64);
    List<ArchivoAdjunto> onc01 = new ArrayList<>(64);
    List<ArchivoAdjunto> onc02 = new ArrayList<>(64);

    String minFechaOnc01;
    String minFechaOnc02;

    List<PlazoComun> plazosOnc01 = new ArrayList<>();
    List<PlazoComun> plazosOnc02 = new ArrayList<>();

    OrganismoPack(String organismo) {
      this.organismo = organismo;
    }
  }

  public static class PlazoComun {
    public final String tipoPlazo;
    public final String fechaInicio;
    public final String fechaFin;

    public PlazoComun(String t, String i, String f) {
      tipoPlazo = t;
      fechaInicio = i;
      fechaFin = f;
    }

    @Override
    public String toString() {
      return "PlazoComun{tipo='" + tipoPlazo + "', inicio='" + fechaInicio + "', fin='" + fechaFin + "'}";
    }
  }
}