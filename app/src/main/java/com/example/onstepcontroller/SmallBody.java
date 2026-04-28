package com.example.onstepcontroller;

final class SmallBody {
    final String designation;
    final String nameZh;
    final String nameEn;
    final boolean isComet;
    final double epochJd;
    final double perihelionDistanceAu;
    final double eccentricity;
    final double inclinationDeg;
    final double ascendingNodeDeg;
    final double argumentPerihelionDeg;
    final double tpJd;
    final double absoluteMagnitude;
    final double slope;

    SmallBody(String designation, String nameZh, String nameEn, boolean isComet,
              double epochJd, double perihelionDistanceAu, double eccentricity,
              double inclinationDeg, double ascendingNodeDeg, double argumentPerihelionDeg,
              double tpJd, double absoluteMagnitude, double slope) {
        this.designation = designation;
        this.nameZh = nameZh == null ? "" : nameZh;
        this.nameEn = nameEn == null ? "" : nameEn;
        this.isComet = isComet;
        this.epochJd = epochJd;
        this.perihelionDistanceAu = perihelionDistanceAu;
        this.eccentricity = eccentricity;
        this.inclinationDeg = inclinationDeg;
        this.ascendingNodeDeg = ascendingNodeDeg;
        this.argumentPerihelionDeg = argumentPerihelionDeg;
        this.tpJd = tpJd;
        this.absoluteMagnitude = absoluteMagnitude;
        this.slope = slope;
    }

    String displayLabel() {
        if (!nameZh.isEmpty()) {
            return nameZh;
        }
        if (!nameEn.isEmpty()) {
            return nameEn;
        }
        return designation;
    }

    String bodyId() {
        return (isComet ? "comet:" : "asteroid:") + designation;
    }
}
