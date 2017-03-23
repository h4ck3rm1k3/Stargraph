package net.stargraph.core.query;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import net.stargraph.Language;
import net.stargraph.UnsupportedLanguageException;
import net.stargraph.core.query.nli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Rules {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private Marker marker = MarkerFactory.getMarker("qa");

    private Map<Language, List<DataModelTypePattern>> dataModelTypePatterns;
    private Map<Language, List<QueryPlanPattern>> queryPlanPatterns;
    private Map<Language, List<Pattern>> stopPatterns;
    private Map<Language, List<QueryTypePattern>> queryTypePatterns;

    public Rules(Config config) {
        logger.info(marker, "Loading Rules.");
        this.dataModelTypePatterns = loadDataModelTypePatterns(Objects.requireNonNull(config));
        this.queryPlanPatterns = loadQueryPlanPatterns(Objects.requireNonNull(config));
        this.stopPatterns = loadStopPatterns(config);
        this.queryTypePatterns = loadQueryTypePatterns(config);
    }

    public List<DataModelTypePattern> getDataModelTypeRules(Language language) {
        if (dataModelTypePatterns.containsKey(language)) {
            return dataModelTypePatterns.get(language);
        }
        throw new UnsupportedLanguageException(language);
    }

    public List<QueryPlanPattern> getQueryPlanRules(Language language) {
        if (queryPlanPatterns.containsKey(language)) {
            return queryPlanPatterns.get(language);
        }
        throw new UnsupportedLanguageException(language);
    }

    public List<Pattern> getStopRules(Language language) {
        if (stopPatterns.containsKey(language)) {
            return stopPatterns.get(language);
        }
        throw new UnsupportedLanguageException(language);
    }

    public List<QueryTypePattern> getQueryTypeRules(Language language) {
        if (queryTypePatterns.containsKey(language)) {
            return queryTypePatterns.get(language);
        }
        throw new UnsupportedLanguageException(language);
    }

    private Map<Language, List<DataModelTypePattern>> loadDataModelTypePatterns(Config config) {
        Map<Language, List<DataModelTypePattern>> rulesByLang = new HashMap<>();
        ConfigObject configObject = config.getObject("rules.syntatic-pattern");

        configObject.keySet().forEach(strLang -> {
            Language language = Language.valueOf(strLang.toUpperCase());

            rulesByLang.compute(language, (l, r) -> {

                List<DataModelTypePattern> rules = new ArrayList<>();
                List<? extends Config> patternCfg = configObject.toConfig().getConfigList(strLang);

                for (Config cfg : patternCfg) {
                    Map.Entry<String, ConfigValue> entry = cfg.entrySet().stream().findFirst().orElse(null);
                    String modelStr = entry.getKey();
                    @SuppressWarnings("unchecked")
                    List<String> patternList = (List<String>) entry.getValue().unwrapped();
                    rules.addAll(patternList.stream()
                            .map(p -> new DataModelTypePattern(p, DataModelType.valueOf(modelStr)))
                            .collect(Collectors.toList()));
                }

                logger.trace(marker, "All rules for '{}': {}", l, rules);

                logger.info(marker, "Loaded {} Data Model Type patterns for '{}'", rules.size(), l);

                return rules;
            });
        });


        return rulesByLang;
    }

    @SuppressWarnings("unchecked")
    private Map<Language, List<QueryPlanPattern>> loadQueryPlanPatterns(Config config) {
        Map<Language, List<QueryPlanPattern>> rulesByLang = new LinkedHashMap<>();
        ConfigObject configObject = config.getObject("rules.planner-pattern");

        configObject.keySet().forEach(strLang -> {
            Language language = Language.valueOf(strLang.toUpperCase());
            List<QueryPlanPattern> plans = new ArrayList<>();

            List<? extends ConfigObject> innerCfg = configObject.toConfig().getObjectList(strLang);
            innerCfg.forEach(e -> {
                Map<String, Object> plan = e.unwrapped();
                String planId = plan.keySet().toArray(new String[1])[0];
                List<String> triplePatterns = (List<String>)plan.values().toArray()[0];
                plans.add(new QueryPlanPattern(planId, triplePatterns));
            });

            rulesByLang.put(language, plans);
        });

        return rulesByLang;
    }

    private Map<Language, List<Pattern>> loadStopPatterns(Config config) {
        Map<Language, List<Pattern>> rulesByLang = new LinkedHashMap<>();
        ConfigObject configObject = config.getObject("rules.stop-pattern");

        configObject.keySet().forEach(strLang -> {
            Language language = Language.valueOf(strLang.toUpperCase());
            List<String> patternStr = configObject.toConfig().getStringList(strLang);

            rulesByLang.compute(language,
                    (lang, pattern) -> patternStr.stream().map(Pattern::compile).collect(Collectors.toList()));

            logger.info(marker, "Loaded {} Stop patterns for '{}'", rulesByLang.get(language).size(), language);

        });

        return rulesByLang;
    }

    private Map<Language, List<QueryTypePattern>> loadQueryTypePatterns(Config config) {
        Map<Language, List<QueryTypePattern>> rulesByLang = new HashMap<>();
        ConfigObject configObject = config.getObject("rules.query-pattern");

        configObject.keySet().forEach(strLang -> {
            Language language = Language.valueOf(strLang.toUpperCase());

            ConfigObject innerCfg = configObject.toConfig().getObject(strLang);

            List<QueryTypePattern> patterns = new ArrayList<>();

            rulesByLang.compute(language, (l, q) -> {
                innerCfg.keySet().forEach(key -> {
                    QueryType queryType = QueryType.valueOf(key);
                    List<String> patternStr = innerCfg.toConfig().getStringList(key);
                    patterns.add(new QueryTypePattern(queryType,
                            patternStr.stream().map(Pattern::compile).collect(Collectors.toList())));
                });

                logger.info(marker, "Loaded {} Query Type patterns for '{}'", patterns.size(), language);
                return patterns;
            });

        });

        return rulesByLang;
    }
}