// =====================================================================
//  Babyfoon behuizing - 303ESP32S3-AI v2.3 ("Xiaozhi AI Voice Chat")
//  Parametrisch OpenSCAD-ontwerp, versie 0.1 (GESCHATTE MATEN)
//
//  Alle maten met "SCHATTING" zijn afgeleid van de productfoto.
//  Meet ze na met een schuifmaat zodra het bordje binnen is,
//  pas ze hier aan en exporteer opnieuw (F6, daarna F7 = STL).
//
//  Assenstelsel: oorsprong = linksonder op de printplaat,
//  van bovenaf gezien zoals op de productfoto:
//   - links  = USB-C, speaker- en batterijconnector
//   - rechts = knoppen
//   - onder  = aan/uit-schuifje
//   - boven  = antenne / microfoon linksboven
// =====================================================================

/* [Wat printen] */
deel = "beide";            // [beide, onderbak, deksel, samengesteld]

/* [Printplaat] */
pcb_w = 39.0;              // breedte (x)
pcb_d = 45.5;              // diepte (y)
pcb_t = 1.6;               // dikte
comp_h = 8.0;              // SCHATTING: hoogste component boven de print
gat_inset = 2.5;           // SCHATTING: hart schroefgat tot rand
gat_d = 1.8;               // gat in standoff (M2 zelftappend)

/* [Speaker (onder de print)] */
spk_d = 40.0;              // SCHATTING: diameter ronde speaker
spk_h = 5.0;               // SCHATTING: dikte speaker
spk_x = 21.0;              // middelpunt speaker (x)
spk_y = 22.75;             // middelpunt speaker (y)

/* [Microfoon] */
mic_x = 5.8;               // SCHATTING
mic_y = 42.8;              // SCHATTING
mic_poort = "boven";       // [boven, onder] geluidsgat boven of onder de mic?

/* [Knoppen (van rechtsboven naar beneden)] */
knop_x = 34.7;             // SCHATTING
knop_wifi_y = 38.6;        // SCHATTING
knop_volp_y = 33.1;        // SCHATTING
knop_volm_y = 27.6;        // SCHATTING
knop_boot_y = 22.1;        // SCHATTING
knop_en_y  = 16.7;         // SCHATTING
knop_h = 5.0;              // SCHATTING: hoogte drukknop boven de print

/* [Aansluitingen] */
usb_y = 12.5;              // SCHATTING: hart USB-C (linkerkant)
usb_gat_w = 13.0;          // ruim, zodat de stekker erin past
usb_gat_h = 7.0;
schakelaar_x = 19.4;       // SCHATTING: aan/uit-schuifje (onderkant)
schakelaar_gat_w = 10.0;

/* [Behuizing] */
wand = 2.0;
vloer = 2.0;
deksel_t = 2.0;
ruimte_links = 7.0;        // ruimte voor de PH2.0-stekkers
ruimte_rest = 1.0;         // speling rondom aan de andere kanten
speling = 0.2;             // passing deksel
voetjes = true;            // geeft ruimte voor het geluid van de speaker
sleutelgaten = true;       // ophangen aan de muur

$fn = 48;

// ---------- afgeleide maten ----------
standoff_h = spk_h + 2.0;                 // print boven de speaker
pcb_z      = vloer + standoff_h;          // onderkant print
pcb_top    = pcb_z + pcb_t;
binnen_x0  = -ruimte_links;
binnen_y0  = -ruimte_rest;
binnen_w   = pcb_w + ruimte_links + ruimte_rest;
binnen_d   = pcb_d + 2 * ruimte_rest;
binnen_top = pcb_top + comp_h;            // onderkant deksel
buiten_x0  = binnen_x0 - wand;
buiten_y0  = binnen_y0 - wand;
buiten_w   = binnen_w + 2 * wand;
buiten_d   = binnen_d + 2 * wand;

gaten = [[gat_inset, gat_inset], [pcb_w - gat_inset, gat_inset],
         [gat_inset, pcb_d - gat_inset], [pcb_w - gat_inset, pcb_d - gat_inset]];

module rbox(x, y, z, r = 2) {
    // afgeronde doos (alleen verticale randen)
    hull() for (i = [r, x - r], j = [r, y - r])
        translate([i, j, 0]) cylinder(r = r, h = z);
}

// =====================================================================
//  ONDERBAK
// =====================================================================
module onderbak() {
    difference() {
        union() {
            // buitenkant
            translate([buiten_x0, buiten_y0, 0])
                rbox(buiten_w, buiten_d, binnen_top);
        }
        // binnenruimte
        translate([binnen_x0, binnen_y0, vloer])
            rbox(binnen_w, binnen_d, binnen_top, 1);

        // USB-C (linkerwand)
        translate([buiten_x0 - 1, usb_y - usb_gat_w / 2, pcb_top + 1.6 - usb_gat_h / 2])
            cube([wand + 2, usb_gat_w, usb_gat_h]);

        // aan/uit-schuifje (voorwand)
        translate([schakelaar_x - schakelaar_gat_w / 2, buiten_y0 - 1, pcb_z - 1])
            cube([schakelaar_gat_w, wand + 2, pcb_t + 5]);

        // speakerrooster in de vloer
        for (r = [0 : 3.5 : spk_d / 2 - 4])
            for (a = [0 : (r == 0 ? 360 : 360 / floor(2 * PI * r / 3.5)) : 359])
                translate([spk_x + r * cos(a), spk_y + r * sin(a), -1])
                    cylinder(d = 2.0, h = vloer + 2);

        // microfoon met poort aan de onderkant: gat in de vloer
        if (mic_poort == "onder")
            translate([mic_x, mic_y, -1]) cylinder(d = 2.0, h = vloer + 2);

        // sleutelgaten in de strook links (naast de speaker)
        if (sleutelgaten)
            for (y = [8, pcb_d - 8]) translate([-ruimte_links / 2 - 0.5, y, -1]) {
                cylinder(d = 7.5, h = vloer + 2);
                hull() {
                    cylinder(d = 3.8, h = vloer + 2);
                    translate([0, 6, 0]) cylinder(d = 3.8, h = vloer + 2);
                }
            }
    }

    // standoffs voor de print
    for (p = gaten) translate([p[0], p[1], vloer])
        difference() {
            cylinder(d = 5, h = standoff_h);
            cylinder(d = gat_d, h = standoff_h + 1);
        }

    // ring die de speaker op zijn plek houdt
    // (afgesneden waar hij de wand raakt)
    intersection() {
        translate([spk_x, spk_y, vloer]) difference() {
            cylinder(d = spk_d + 3.2, h = 2);
            translate([0, 0, -1]) cylinder(d = spk_d + 0.6, h = 4);
        }
        translate([binnen_x0, binnen_y0, vloer]) cube([binnen_w, binnen_d, 3]);
    }

    // akoestisch kokertje naar de mic (onder-poort)
    if (mic_poort == "onder")
        translate([mic_x, mic_y, vloer]) difference() {
            cylinder(d = 5, h = standoff_h - 0.3);
            translate([0, 0, -1]) cylinder(d = 2.0, h = standoff_h + 2);
        }

    // voetjes (3 mm), ook als afstandhouder tegen de muur
    if (voetjes)
        for (i = [buiten_x0 + 5, buiten_x0 + buiten_w - 5],
             j = [buiten_y0 + 5, buiten_y0 + buiten_d - 5])
            translate([i, j, -3]) cylinder(d1 = 5, d2 = 6, h = 3);
}

// =====================================================================
//  DEKSEL
// =====================================================================
knoppen_druk = [knop_wifi_y, knop_volp_y, knop_volm_y];

module deksel() {
    rand = 3; // diepte van de rand die in de bak valt
    difference() {
        union() {
            // plaat
            translate([buiten_x0, buiten_y0, binnen_top])
                rbox(buiten_w, buiten_d, deksel_t);
            // rand die in de bak klemt
            translate([binnen_x0 + speling, binnen_y0 + speling, binnen_top - rand])
                difference() {
                    rbox(binnen_w - 2 * speling, binnen_d - 2 * speling, rand, 1);
                    translate([1.6, 1.6, -1])
                        rbox(binnen_w - 2 * speling - 3.2, binnen_d - 2 * speling - 3.2, rand + 2, 1);
                }
        }
        // microfoongat
        if (mic_poort == "boven")
            translate([mic_x, mic_y, binnen_top - 1]) cylinder(d = 2.0, h = deksel_t + 2);

        // flexknoppen: U-vormige sleuf rond een tongetje
        // scharnier links (richting midden), vrij uiteinde boven de knop
        for (y = knoppen_druk) translate([knop_x, y, binnen_top - 1])
            difference() {
                translate([-8, -2.3, 0]) cube([11.6, 4.6, deksel_t + 2]);
                translate([-9, -1.8, -1]) cube([12.0, 3.6, deksel_t + 4]);
            }

        // BOOT en EN: alleen een klein gaatje (met paperclip bedienen)
        for (y = [knop_boot_y, knop_en_y])
            translate([knop_x, y, binnen_top - 1]) cylinder(d = 1.6, h = deksel_t + 2);

        // markering: puntje bij de flexknoppen
        for (i = [0 : 2]) translate([knop_x - 11, knoppen_druk[i], binnen_top + deksel_t - 0.6])
            cylinder(d = 1.6, h = 1);
    }

    // stiftjes onder de flexknoppen
    stift_l = comp_h - knop_h - 0.3;
    for (y = knoppen_druk) translate([knop_x, y, binnen_top - stift_l])
        cylinder(d = 3, h = stift_l + 0.01);

    // akoestisch kokertje van deksel naar mic (vermindert rondzingen)
    if (mic_poort == "boven")
        translate([mic_x, mic_y, pcb_top + 1.5]) difference() {
            cylinder(d = 5, h = binnen_top - pcb_top - 1.5);
            translate([0, 0, -1]) cylinder(d = 2.0, h = comp_h + 2);
        }
}

// =====================================================================
//  printplaat als spookbeeld (alleen bij "samengesteld")
// =====================================================================
module pcb_spook() {
    %translate([0, 0, pcb_z]) difference() {
        cube([pcb_w, pcb_d, pcb_t]);
        for (p = gaten) translate([p[0], p[1], -1]) cylinder(d = 2.2, h = 4);
    }
    %translate([spk_x, spk_y, vloer]) cylinder(d = spk_d, h = spk_h);
}

// ---------- uitvoer ----------
// deksel ondersteboven (bovenkant op het printbed)
module deksel_print() {
    translate([0, 2 * buiten_y0 + buiten_d, binnen_top + deksel_t])
        rotate([180, 0, 0]) deksel();
}

if (deel == "onderbak") onderbak();
else if (deel == "deksel") deksel_print();
else if (deel == "samengesteld") { onderbak(); deksel(); pcb_spook(); }
else {
    translate([0, 0, voetjes ? 3 : 0]) onderbak();
    translate([buiten_w + 8, 0, 0]) deksel_print();
}
