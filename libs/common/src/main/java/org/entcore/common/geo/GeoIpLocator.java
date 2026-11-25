package org.entcore.common.geo;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Géolocalisation d'une adresse IP à partir d'une base locale au format {@code .mmdb}
 * (DB-IP « IP to City Lite », licence CC-BY 4.0 : la mention « IP Geolocation by DB-IP »
 * doit accompagner tout affichage de ces données).
 *
 * <p>Aucun appel réseau : la base est un fichier posé sur le disque du conteneur et
 * remplacé chaque mois. Le fichier est relu à chaud quand sa date de modification change.</p>
 *
 * <p>Sans base disponible, {@link #locate(String)} renvoie {@code null} : rien ne doit
 * dépendre de la géolocalisation pour fonctionner, en particulier pas la connexion.</p>
 *
 * <p>Chemin : variable d'environnement {@code GEOIP_DB}, sinon
 * {@value #DEFAULT_PATH}.</p>
 */
public final class GeoIpLocator {

    private static final Logger log = LoggerFactory.getLogger(GeoIpLocator.class);

    public static final String DEFAULT_PATH = "/srv/geoip/dbip-city-lite.mmdb";
    /** Intervalle minimal entre deux vérifications de la date du fichier. */
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private static volatile GeoIpLocator instance;

    private final File file;
    private volatile Reader reader;
    private volatile long loadedModified = -1L;
    private volatile long lastCheck = 0L;

    GeoIpLocator(final String path) {
        this.file = new File(path);
    }

    public static GeoIpLocator getInstance() {
        if (instance == null) {
            synchronized (GeoIpLocator.class) {
                if (instance == null) {
                    instance = new GeoIpLocator(resolvePath());
                }
            }
        }
        return instance;
    }

    /**
     * {@code GEOIP_DB} s'il est défini (déploiement Helm), sinon {@code $BASE_PATH/storage/geoip/}
     * s'il y a une base (launcher local : même emplacement relatif qu'en production), sinon
     * {@value #DEFAULT_PATH}.
     */
    private static String resolvePath() {
        final String env = System.getenv("GEOIP_DB");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        final String basePath = System.getenv("BASE_PATH");
        if (basePath != null && !basePath.trim().isEmpty()) {
            final File local = new File(basePath.trim(), "storage/geoip/dbip-city-lite.mmdb");
            if (local.isFile()) {
                return local.getPath();
            }
        }
        // Launcher local lancé depuis starter/ sans BASE_PATH dans l'environnement du processus
        final File relative = new File("storage/geoip/dbip-city-lite.mmdb");
        if (relative.isFile()) {
            return relative.getAbsolutePath();
        }
        return DEFAULT_PATH;
    }

    /** Remplace la base utilisée (tests, ou chemin lu dans la configuration d'un module). */
    public static synchronized void configure(final String path) {
        if (path != null && !path.trim().isEmpty()) {
            instance = new GeoIpLocator(path.trim());
        }
    }

    public boolean isAvailable() {
        return currentReader() != null;
    }

    /**
     * Première adresse d'un en-tête {@code X-Forwarded-For} (« client, proxy1, proxy2 »),
     * débarrassée des espaces et d'un éventuel port IPv4. Renvoie {@code null} si vide.
     */
    public static String normalizeIp(final String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.split(",")[0].trim();
        if (ip.isEmpty()) {
            return null;
        }
        if (ip.startsWith("[") && ip.contains("]")) {
            ip = ip.substring(1, ip.indexOf(']'));
        } else if (ip.indexOf(':') > 0 && ip.indexOf(':') == ip.lastIndexOf(':') && ip.contains(".")) {
            ip = ip.substring(0, ip.indexOf(':'));
        }
        return ip;
    }

    /** Vrai pour une adresse non routable sur Internet (réseau interne, boucle locale…). */
    public static boolean isPrivate(final InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        final byte[] b = address.getAddress();
        // fc00::/7 (adresses IPv6 uniques locales) et 100.64.0.0/10 (NAT opérateur)
        return (b.length == 16 && (b[0] & 0xfe) == 0xfc)
                || (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64);
    }

    /**
     * Localise une adresse IP.
     *
     * @return {@code {ip, country, countryName, region, city, lat, lon}} (champs absents
     * s'ils sont inconnus), {@code {ip, private: true}} pour une adresse interne,
     * ou {@code null} si l'adresse est invalide ou la base indisponible
     */
    public JsonObject locate(final String rawIp) {
        final String ip = normalizeIp(rawIp);
        if (ip == null || !looksLikeIp(ip)) {
            return null;
        }
        final InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (Exception e) {
            return null;
        }
        if (isPrivate(address)) {
            return new JsonObject().put("ip", ip).put("private", true);
        }
        final Reader r = currentReader();
        if (r == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> record = r.get(address, Map.class);
            final JsonObject result = new JsonObject().put("ip", ip);
            if (record == null) {
                return result;
            }
            final Map<String, Object> country = map(record.get("country"));
            if (country != null) {
                putIfNotNull(result, "country", country.get("iso_code"));
                putIfNotNull(result, "countryName", name(country));
            }
            final Map<String, Object> city = map(record.get("city"));
            if (city != null) {
                putIfNotNull(result, "city", name(city));
            }
            final Object subdivisions = record.get("subdivisions");
            if (subdivisions instanceof List && !((List<?>) subdivisions).isEmpty()) {
                putIfNotNull(result, "region", name(map(((List<?>) subdivisions).get(0))));
            }
            final Map<String, Object> location = map(record.get("location"));
            if (location != null) {
                if (location.get("latitude") instanceof Number) {
                    result.put("lat", ((Number) location.get("latitude")).doubleValue());
                }
                if (location.get("longitude") instanceof Number) {
                    result.put("lon", ((Number) location.get("longitude")).doubleValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("GeoIP lookup failed for " + ip + " : " + e.getMessage());
            return null;
        }
    }

    /** Code pays ISO-3166 alpha-2 de l'adresse, ou {@code null}. */
    public String country(final String rawIp) {
        final JsonObject geo = locate(rawIp);
        return geo != null ? geo.getString("country") : null;
    }

    private static boolean looksLikeIp(final String ip) {
        // Évite toute résolution DNS par InetAddress.getByName sur un nom d'hôte
        return ip.matches("[0-9.]+") || (ip.contains(":") && ip.matches("[0-9a-fA-F:.]+"));
    }

    private Reader currentReader() {
        final long now = System.currentTimeMillis();
        if (reader != null && now - lastCheck < CHECK_INTERVAL_MS) {
            return reader;
        }
        synchronized (this) {
            lastCheck = now;
            if (!file.isFile()) {
                if (reader == null && loadedModified != -2L) {
                    log.info("GeoIP database not found at " + file.getPath() + " : geolocation disabled");
                    loadedModified = -2L;
                }
                return reader;
            }
            final long modified = file.lastModified();
            if (reader == null || modified != loadedModified) {
                try {
                    final Reader previous = reader;
                    reader = new Reader(file, new CHMCache());
                    loadedModified = modified;
                    log.info("GeoIP database loaded from " + file.getPath());
                    if (previous != null) {
                        previous.close();
                    }
                } catch (Exception e) {
                    log.error("Unable to load GeoIP database " + file.getPath(), e);
                }
            }
            return reader;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Object name(final Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        final Map<String, Object> names = map(node.get("names"));
        if (names == null) {
            return null;
        }
        return names.get("fr") != null ? names.get("fr") : names.get("en");
    }

    private static void putIfNotNull(final JsonObject json, final String key, final Object value) {
        if (value != null) {
            json.put(key, value.toString());
        }
    }
}
