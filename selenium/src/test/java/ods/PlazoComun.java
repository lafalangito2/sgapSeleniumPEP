package ods;

public class PlazoComun {
    public String tipo;
    public String fechaIni;
    public String fechaFin;

    public PlazoComun(String tipo, String fechaIni, String fechaFin) {
        this.tipo = tipo;
        this.fechaIni = fechaIni;
        this.fechaFin = fechaFin;
    }

    @Override
    public String toString() {
        return "PlazoComun{tipo='" + tipo + "', fechaIni='" + fechaIni + "', fechaFin='" + fechaFin + "'}";
    }
}

