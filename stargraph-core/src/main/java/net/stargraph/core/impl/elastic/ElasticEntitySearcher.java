package net.stargraph.core.impl.elastic;

/*-
 * ==========================License-Start=============================
 * stargraph-core
 * --------------------------------------------------------------------
 * Copyright (C) 2017 Lambda^3
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ==========================License-End===============================
 */

import net.stargraph.core.Namespace;
import net.stargraph.core.Stargraph;
import net.stargraph.core.search.EntitySearcher;
import net.stargraph.core.search.Searcher;
import net.stargraph.model.*;
import net.stargraph.rank.*;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.index.query.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.elasticsearch.index.query.QueryBuilders.*;

public final class ElasticEntitySearcher implements EntitySearcher {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private Marker marker = MarkerFactory.getMarker("elastic");

    private Stargraph core;

    public ElasticEntitySearcher(Stargraph core) {
        this.core = Objects.requireNonNull(core);
    }

    @Override
    public LabeledEntity getEntity(String dbId, String id) {
        List<LabeledEntity> res = getEntities(dbId, Collections.singletonList(id));
        if (res != null && !res.isEmpty()) {
            return res.get(0);
        }
        return null;
    }

    @Override
    public List<LabeledEntity> getEntities(String dbId, List<String> ids) {
        logger.info(marker, "Fetching ids={}", ids);
        Namespace ns = core.getNamespace(dbId);
        List idList = ids.stream().map(ns::shrinkURI).collect(Collectors.toList());
        ModifiableSearchParams searchParams = ModifiableSearchParams.create(dbId).model(BuiltInModel.ENTITY);
        QueryBuilder queryBuilder = termsQuery("id", idList);
        Searcher searcher = core.getSearcher(searchParams.getKbId());
        Scores scores = searcher.search(new ElasticQueryHolder(queryBuilder, searchParams));
        return scores.stream().map(s -> (LabeledEntity)s.getEntry()).collect(Collectors.toList());
    }

    @Override
    public Scores classSearch(ModifiableSearchParams searchParams, ModifiableRankParams rankParams) {

        searchParams.model(BuiltInModel.FACT);

        if (rankParams instanceof ModifiableIndraParams) {
            configureDistributionalParams(searchParams.getKbId(), (ModifiableIndraParams) rankParams);
        }

        QueryBuilder queryBuilder = boolQuery()
                .must(nestedQuery("p",
                        termQuery("p.id", "is-a"),  ScoreMode.Max))
                .should(nestedQuery("o",
                        matchQuery("o.value", searchParams.getSearchTerm()),  ScoreMode.Max))
                .minimumShouldMatch("1");

        Searcher searcher = core.getSearcher(searchParams.getKbId());
        Scores scores = searcher.search(new ElasticQueryHolder(queryBuilder, searchParams));

        List<Score> classes2Score = scores.stream()
                .map(s -> new Score(((Fact)s.getEntry()).getObject(), s.getValue())).collect(Collectors.toList());

        return Rankers.apply(new Scores(classes2Score), rankParams, searchParams.getSearchTerm());
    }

    @Override
    public Scores instanceSearch(ModifiableSearchParams searchParams, ModifiableRankParams rankParams) {
        // Coupling point: the query tied with our backend ..
        QueryBuilder queryBuilder = matchQuery("value", searchParams.getSearchTerm());
        // .. and at this point we add the missing information specific for this kind of search
        searchParams.model(BuiltInModel.ENTITY);
        // Fetch the 'generic' searcher instance
        Searcher searcher = core.getSearcher(searchParams.getKbId());
        // Fetch initial candidates from the search engine
        Scores scores = searcher.search(new ElasticQueryHolder(queryBuilder, searchParams));
        // Re-Rank
        return Rankers.apply(scores, rankParams, searchParams.getSearchTerm());
    }

    @Override
    public Scores propertySearch(ModifiableSearchParams searchParams, ModifiableRankParams rankParams) {

        searchParams.model(BuiltInModel.PROPERTY);

        if (rankParams instanceof ModifiableIndraParams) {
            configureDistributionalParams(searchParams.getKbId(), (ModifiableIndraParams) rankParams);
        }

        QueryBuilder queryBuilder = boolQuery()
                .should(nestedQuery("hyponyms",
                        matchQuery("hyponyms.word", searchParams.getSearchTerm()), ScoreMode.Max))
                .should(nestedQuery("hypernyms",
                        matchQuery("hypernyms.word", searchParams.getSearchTerm()), ScoreMode.Max))
                .should(nestedQuery("synonyms",
                        matchQuery("synonyms.word", searchParams.getSearchTerm()), ScoreMode.Max))
                .minimumNumberShouldMatch(1);

        Searcher searcher = core.getSearcher(searchParams.getKbId());
        Scores scores = searcher.search(new ElasticQueryHolder(queryBuilder, searchParams));

        return Rankers.apply(scores, rankParams, searchParams.getSearchTerm());
    }

    @Override
    public Scores pivotedSearch(InstanceEntity pivot,
                                ModifiableSearchParams searchParams, ModifiableRankParams rankParams) {

        searchParams.model(BuiltInModel.FACT);

        if (rankParams instanceof ModifiableIndraParams) {
            configureDistributionalParams(searchParams.getKbId(), (ModifiableIndraParams) rankParams);
        }

        QueryBuilder queryBuilder = boolQuery()
                .should(nestedQuery("s", termQuery("s.id", pivot.getId()), ScoreMode.Max))
                .should(nestedQuery("o", termQuery("o.id", pivot.getId()), ScoreMode.Max)).minimumNumberShouldMatch(1);

        Searcher searcher = core.getSearcher(searchParams.getKbId());
        Scores scores = searcher.search(new ElasticQueryHolder(queryBuilder, searchParams));

        // We have to remap the facts to properties, the real target of the ranker call.
        // Thus we're discarding the score values from the underlying search engine. Shall we?
        Scores propScores = new Scores(scores.stream()
                .map(s -> ((Fact) s.getEntry()).getPredicate())
                .distinct()
                .map(p -> new Score(p, 0))
                .collect(Collectors.toList()));

        return Rankers.apply(propScores, rankParams, searchParams.getSearchTerm());
    }

    private void configureDistributionalParams(KBId kbId, ModifiableIndraParams params) {
        String indraUrl = core.getConfig().getString("distributional-service.rest-url");
        String indraCorpus = core.getConfig().getString("distributional-service.corpus");
        String lang = core.getKBConfig(kbId).getString("language");
        params.url(indraUrl).corpus(indraCorpus).language(lang);
    }
}
