package ods;

public class OncRegistro {
    public String fechaPub;
    public String estado;
    public String nombreNormalizado;
    public String nombreParticular;

    public OncRegistro(String fechaPub, String estado, String nombreNormalizado, String nombreParticular) {
        this.fechaPub = fechaPub;
        this.estado = estado;
        this.nombreNormalizado = nombreNormalizado;
        this.nombreParticular = nombreParticular;
    }

    @Override
    public String toString() {
        return "OncRegistro{fechaPub='" + fechaPub + "', estado='" + estado
                + "', nombreNormalizado='" + nombreNormalizado
                + "', nombreParticular='" + nombreParticular + "'}";
    }
}
