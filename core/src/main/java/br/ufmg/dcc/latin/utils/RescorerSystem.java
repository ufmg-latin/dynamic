package br.ufmg.dcc.latin.utils;


import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.search.similarities.BoostedBasicStats;
import org.apache.lucene.util.BytesRef;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntDoubleHashMap;

public class RescorerSystem {

	
	public static double[] sim(int[] docids, int docid){
		
		int n = docids.length;
		
		double[] scores = new double[n];
		int count = (int) SharedCache.directedIndex[0].docCount;
		
		for (int i = 0; i < docids.length; i++) {
			scores[i] = tfidf(i,docid,count, SharedCache.vocab[0]);
		}
		
	    return scores;
	}
	
	private static TIntDoubleHashMap getComplexQuery(String query){
		TIntArrayList terms = tokenizeString(new EnglishAnalyzer(), query);
		TIntDoubleHashMap queryProb = new TIntDoubleHashMap();
		
		for (int i = 0; i < terms.size(); i++) {
			if (queryProb.contains(terms.get(i))){
				queryProb.put(terms.get(i), queryProb.get(i)+1);
			} else {
				queryProb.put(terms.get(i),1);
			}
		}
		
		int[] uniqTerms = queryProb.keys();
		
		for (int i = 0; i < uniqTerms.length ; i++) {	
			queryProb.put(uniqTerms[i], queryProb.get(uniqTerms[i])/(float) terms.size());
		}
		
		return queryProb;
	}
	
	private static BoostedBasicStats[] stats;
	
	public static double[] rescore(String query) {
		
		TIntDoubleHashMap complexQuery = getComplexQuery(query);
		
		int n = SharedCache.docids.length;
		
		double[] scores = new double[n];
		
		int[] terms = complexQuery.keys();
		
		if (stats == null) {
			stats = new BoostedBasicStats[2];
			stats[1] = new BoostedBasicStats("content");
			stats[0] = new BoostedBasicStats("title");
			
			float sttf = ((Number) SharedCache.directedIndex[0].sumTotalTermFreq).floatValue();
			stats[0].setNumberOfDocuments(SharedCache.directedIndex[0].docCount);
			float avgFieldLength  = (float) (sttf / stats[0].getNumberOfDocuments());
			stats[0].setAvgFieldLength(avgFieldLength);
			stats[0].setNumberOfFieldTokens((long) SharedCache.directedIndex[0].sumTotalTermFreq);
			sttf = ((Number) SharedCache.directedIndex[1].sumTotalTermFreq).floatValue();
			stats[1].setNumberOfDocuments(SharedCache.directedIndex[1].docCount);
			avgFieldLength  = (float) (sttf / stats[1].getNumberOfDocuments());
			stats[1].setAvgFieldLength(avgFieldLength);
			stats[1].setNumberOfFieldTokens((long) SharedCache.directedIndex[1].sumTotalTermFreq);
			
		}
		for (int i = 0; i < terms.length; i++) {
			
			
			stats[0].setBoost((float) (complexQuery.get(terms[i])*SharedCache.fieldWeights[1]));
			stats[0].setDocFreq(SharedCache.termStats[0].docFreq[terms[i]]);
			stats[0].setTotalTermFreq(SharedCache.termStats[0].totalTermFreq[terms[i]]);
			
			float collectionProbability = (stats[0].getTotalTermFreq()+1F);
			collectionProbability /= (stats[0].getNumberOfFieldTokens()+1F);
			stats[0].setCollectionProbability(collectionProbability);
			
			TIntArrayList docs = SharedCache.directedIndex[0].invertedIndex[terms[i]];
			for (int j = 0; j < docs.size(); j++) {
				int doc = docs.get(j);
				
				int freq = SharedCache.directedIndex[0].docVecs[doc].vec.get(terms[i]);
				int docLen = (int) SharedCache.directedIndex[0].docVecs[doc].docLen;
				if (freq > 0) {
					scores[doc] += RetrievalSystem.similarity.score(stats[0], freq, docLen);
				}
			}

			stats[1].setBoost((float) (complexQuery.get(terms[i])*SharedCache.fieldWeights[0]));
			
			
			stats[1].setDocFreq(SharedCache.termStats[1].docFreq[terms[i]]);
			stats[1].setTotalTermFreq(SharedCache.termStats[1].totalTermFreq[terms[i]]);
			
			collectionProbability = (stats[1].getTotalTermFreq()+1F);
			collectionProbability /= (stats[1].getNumberOfFieldTokens()+1F);
			stats[1].setCollectionProbability(collectionProbability);
			
			docs = SharedCache.directedIndex[1].invertedIndex[terms[i]];
			
			for (int j = 0; j < docs.size(); j++) {
				int doc = docs.get(j);
				int freq = SharedCache.directedIndex[1].docVecs[doc].vec.get(terms[i]);
				int docLen = (int) SharedCache.directedIndex[1].docVecs[doc].docLen;
				if (freq > 0) {
					
					scores[doc] += RetrievalSystem.similarity.score(stats[1], freq, docLen);
				}
			}
			
		}
		
		return scores;
	}
	
	
	public static TIntArrayList tokenizeString(Analyzer analyzer, String str) {
		TIntArrayList result = new TIntArrayList();
		try {
		      TokenStream stream  = analyzer.tokenStream(null, new StringReader(str));
		      stream.reset();
	
		      while (stream.incrementToken()) {
		    	  BytesRef term = new BytesRef(stream.getAttribute(CharTermAttribute.class).toString());
		    	  int termId = SharedCache.vocab[0].getId(term.utf8ToString());
		    	  
		    	  result.add(termId);
		      } 
		      
		      stream.close();
		
		} catch (IOException e) {
			      throw new RuntimeException(e);
		}
		return result;
		
	}

	
	
	private static double tfidf(int docid1, int docid2, int docCount, InMemoryVocabulary vocab){
		double score = 0;
		
		DocVec doc1 = SharedCache.directedIndex[0].docVecs[docid1];
		DocVec doc2 = SharedCache.directedIndex[0].docVecs[docid2];
	
		if (doc1 == null || doc2 == null ){
			return 0;
		}
		
		double docNorm1 = 0;
		double docNorm2 = 0;


		int[] terms = doc1.vec.size() < doc2.vec.size() ? doc1.vec.keys() : doc2.vec.keys();

		for (int i = 0; i < terms.length; i++) {
			
			double tf1 = doc1.vec.get(terms[i]);
			double tf2 = doc2.vec.get(terms[i]);
			double df = SharedCache.termStats[0].docFreq[terms[i]];
			double idf = (double)(Math.log(docCount)/(df+1));
			double weight1 = tf1*idf;
			double weight2 = tf2*idf;
			docNorm1 += weight1*weight1;
			docNorm2 += weight2*weight2;
			
			score += weight1*weight2;
			
		}

		docNorm1 = (double) Math.sqrt(docNorm1);
		docNorm2 = (double) Math.sqrt(docNorm2);


		score /= (docNorm1*docNorm2);
		
		return score;
	}
	
	
	public static double getIdf(String field, int termId){
		
		int count = (int) SharedCache.directedIndex[0].docCount;		
		double df = SharedCache.termStats[0].docFreq[termId];
			
		double idf = (float) (Math.log(count)/(df+1));
		return idf;
	}
	
	
}
