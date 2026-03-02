package ods;

public class ArchivoAdjunto {
  public final String ruta;
  public final String nombre;
  public final String fechaPub;

  /**
   * Puede ser: "" (convocatoria), "01_onc", "02_onc"
   */
  public final String oncCode;

  /**
   * Constructor nuevo: oncCode puede ser "", "01_onc", "02_onc"
   */
  public ArchivoAdjunto(String ruta, String nombre, String fechaPub, String oncCode) {
    this.ruta = ruta;
    this.nombre = nombre;
    this.fechaPub = fechaPub;
    this.oncCode = oncCode == null ? "" : oncCode.trim();
  }

  /**
   * Constructor legacy: true => convocatoria (onc vacío), false => ONC1 por compatibilidad.
   */
  public ArchivoAdjunto(String ruta, String nombre, String fechaPub, boolean oncVacio) {
    this(ruta, nombre, fechaPub, oncVacio ? "" : "01_onc");
  }

  public boolean esConvocatoria() {
    return oncCode == null || oncCode.isBlank();
  }

  public boolean esOnc01() {
    return "01_onc".equalsIgnoreCase(oncCode == null ? "" : oncCode.trim());
  }

  public boolean esOnc02() {
    return "02_onc".equalsIgnoreCase(oncCode == null ? "" : oncCode.trim());
  }

  /**
   * -1 = convocatoria, 0 = onc01, 1 = onc02
   */
  public int ofertaDelta() {
    if (esConvocatoria()) return -1;
    if (esOnc01()) return 0;
    if (esOnc02()) return 1;
    return -1;
  }

  @Override
  public String toString() {
    return "ArchivoAdjunto{ruta='" + ruta + "', nombre='" + nombre + "', fechaPub='" + fechaPub + "', onc='" + oncCode + "'}";
  }
}
