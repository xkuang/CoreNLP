package edu.stanford.nlp.ie.machinereading.domains.nfl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.stanford.nlp.ie.machinereading.BasicEntityExtractor;
import edu.stanford.nlp.ie.machinereading.structure.EntityMention;
import edu.stanford.nlp.ie.machinereading.structure.MachineReadingAnnotations.EntityMentionsAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.CoreAnnotations.BeginIndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.trees.HeadFinder;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;

public class NFLEntityExtractor extends BasicEntityExtractor {
  private static final long serialVersionUID = 1L;
  
  private static final Set<String> annotationsToSkip = new HashSet<String>(Arrays.asList(new String [] {"Date", "teamFinalScore"}));
  
  private HashMap<String, String> gazetteer;

  private static final boolean PREFER_TEAMS_FROM_GAZETTEER = true;
  
  public NFLEntityExtractor(String gazetteerLocation) {
    super(gazetteerLocation, false, annotationsToSkip, false, new NFLEntityMentionFactory());
    gazetteer = NFLGazetteer.loadGazetteer(gazetteerLocation);
    System.err.println("GAZETTEER INFO: loaded " + gazetteer.size() + " entries from file: " + gazetteerLocation);
  }
  
  @Override
  public void postprocessSentence(CoreMap sentence, int sentCount) {
    // our CRF does not recognize dates. Pick the ones from the open-domain NER
    makeAnnotationFromNERTags(sentence, "DATE", "Date");
    
    int origSize = sentence.get(EntityMentionsAnnotation.class).size();
    removeSpuriousMentions(sentence);
    addMissingMentions(sentence, sentCount, origSize);
  }
  
  /**
   * Removes spurious entities that are incorrectly generated by the NER
   * Removes DATEs that are actually game times, e.g., "second half". These are not annotated in NFL
   * Removes DATEs that have no letters and contain a single digit, e.g., "' 1". This is a common NER error
   * If PREFER_TEAMS_FROM_GAZETTEER, removes all NFLTeam mentions (will be added later from gaz)
   * Removes NFLGame that do not have a NN* POS tag
   * @param sentence
   */
  private void removeSpuriousMentions(CoreMap sentence) {
    List<EntityMention> mentions = sentence.get(EntityMentionsAnnotation.class);
    //List<CoreLabel> words = sentence.get(CoreAnnotations.TokensAnnotation.class);
    List<EntityMention> cleanMentions = new ArrayList<EntityMention>();
    for(EntityMention m: mentions){
      if(m.getType().equals("Date") && gameTime(m.getExtentString())){
        logger.info("REMOVING ENTITY MENTION (game time): " + m);
      } else if(m.getType().equals("Date") && invalidDate(m.getExtentString())){
        logger.info("REMOVING ENTITY MENTION (invalid date): " + m);
      } else if(PREFER_TEAMS_FROM_GAZETTEER && m.getType().equals("NFLTeam")){
        logger.info("REMOVING ENTITY MENTION (team predicted by CRF): " + m);
      } 
      /*
      // this helps NER but hurts relations, which are the ultimate goal. disabled for this reason
      else if(m.getType().equals("NFLGame") && ! headedByNN(m, words)) {
        logger.info("REMOVING ENTITY MENTION (game headed by non-NN): " + m);
      } 
      */
      else {
        cleanMentions.add(m);
      }
    }
    sentence.set(EntityMentionsAnnotation.class, cleanMentions);
  }
  
  @SuppressWarnings("unused")
  private static boolean headedByNN(EntityMention m, List<CoreLabel> tokens) {
    int lastPosition = m.getExtentTokenEnd() - 1;
    assert(lastPosition >= m.getExtentTokenStart());
    if(tokens.get(lastPosition).tag().startsWith("NN")) {
      return true;
    }
    return false;
  }
  
  private static final Pattern GAME_TIME = Pattern.compile("half|quarter", Pattern.CASE_INSENSITIVE); 
  
  private boolean gameTime(String s) {
    Matcher m = GAME_TIME.matcher(s);
    if(m.find()) return true;
    return false;
  }
  
  private boolean invalidDate(String s) {
    int letterCount = 0;
    int digitCount = 0;
    for(int i = 0; i < s.length(); i ++){
      if(Character.isLetter(s.charAt(i))) letterCount ++;
      else if(Character.isDigit(s.charAt(i))) digitCount ++;
    }
    if(letterCount == 0 && digitCount < 2) return true;
    return false;
  }
  
  private void addMissingMentions(CoreMap sentence, int sentCount, int identifierOffset) {
    List<CoreLabel> words = sentence.get(CoreAnnotations.TokensAnnotation.class);
    List<EntityMention> mentions = sentence.get(EntityMentionsAnnotation.class);
    
    // pre-processing block below needed only when fetching NFLGame from gazetteer
    /*
    Tree tree = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
    if(tree == null){
      throw new RuntimeException("Syntactic analysis is required for the NFL domain!");
    }
    // convert tree labels to CoreLabel if necessary
    // we need this because we store additional info in the CoreLabel, such as the spans of each tree
    GenericDataSetReader.convertToCoreLabels(tree);
    
    // store the tree spans, if not present already
    CoreLabel l = (CoreLabel) tree.label();
    if(! l.containsKey(BeginIndexAnnotation.class) && ! l.containsKey(EndIndexAnnotation.class)){
      tree.indexSpans(0);
    }
    
    // find all the head words of NP phrases
    Set<Integer> npHeads = new HashSet<Integer>();
    HeadFinder hf = new SemanticHeadFinder();
    extractNpHeads(tree, npHeads, hf);
    
    // find all tokens inside NFLGame
    Set<Integer> gameTokens = new HashSet<Integer>();
    for(EntityMention m: mentions){
      if(m.getType().startsWith("NFL") && m.getType().endsWith("Game")){
        for(int i = m.getExtentTokenStart(); i < m.getExtentTokenEnd(); i ++){
          gameTokens.add(i);
        }
      }
    }
    */
    
    if(PREFER_TEAMS_FROM_GAZETTEER){
      // pick team names from the gazetteer
      for(int start = 0; start < words.size(); start ++){
        String label = null;
        int end = -1;
        for(end = Math.min(start + NFLGazetteer.MAX_MENTION_LENGTH, words.size()); end > start; end --){
          String text = join(words, start, end);
          String gazTag = gazetteer.get(text);
          // String nerTag = findUniqueNerTag(words, start, end);
          if(gazTag != null && gazTag.equals("NFLTeam") /* && ! hasVb */ /* && spanIntersects(start, end, npHeads) */){
            logger.fine("Found entity mention candidate from gazetteer: " + text);
            label = gazTag;
            break;
          } 
          
          /*
          // this improves recall significantly, but drops precision more
          if(gazTag != null && gazTag.equals("NFLGame") && 
              spanIntersects(start, end, npHeads) && // is an NP head 
              ((start > 0 && isPotentialScore(words.get(start - 1))) ||
               (start > 1 && isPotentialScore(words.get(start - 1)))) && // is preceded by a score, e.g., "a 10 to 5 win"
              ! spanIntersects(start, end, gameTokens)){ // is not already an NFLGame
            logger.fine("Found entity mention candidate from gazetteer: " + text);
            label = gazTag;
            break;
          }
          */
        }
        
        // found a candidate for an NFL entity between [start, end)
        if(label != null){
          String id = BasicEntityExtractor.makeEntityMentionIdentifier(sentence, sentCount, identifierOffset);
          identifierOffset ++;
          EntityMention m = makeEntityMention(sentence, start, end, label, id);
          logger.info("ADDED ENTITY MENTION (from gazetteer): " + m);
          start = end - 1;
          mentions.add(m);
        }        
      }
    }
  }
  
  @SuppressWarnings("unused")
  private static boolean isPotentialScore(CoreLabel token) {
    try {
      int value = Integer.valueOf(token.word());
      if(value >= 0 && value < 100) {
        return true;
      }
    } catch(NumberFormatException e) {
    }
    return false;
  }
  
  @SuppressWarnings("unused")
  private static boolean spanIntersects(int start, int end, Set<Integer> heads) {
    for(int i = start; i < end; i ++){
      if(heads.contains(i)){
        return true;
      }
    }
    return false;
  }
  
  @SuppressWarnings("unused")
  private void extractNpHeads(Tree tree, Set<Integer> heads, HeadFinder hf) {
    if(tree.label().value().equals("NP")){
      Tree h = tree.headTerminal(hf);
      logger.info("Found head: " + h.pennString());
      CoreLabel l = (CoreLabel) h.label();
      heads.add(l.get(BeginIndexAnnotation.class));
    }
    
    Tree [] kids = tree.children();
    for(Tree t: kids){
      extractNpHeads(t, heads, hf);
    }
  }
  
  private String join(List<CoreLabel> words, int start, int end) {
    StringBuffer os = new StringBuffer();
    for(int i = start; i < end; i ++){
      if(i > start) os.append(" ");
      os.append(words.get(i).word().toLowerCase());
    }
    return os.toString();
  }
  
  @SuppressWarnings("unused")
  private String findUniqueNerTag(List<CoreLabel> words, int start, int end) {
    String tag = null;
    
    for(int i = start; i < end; i ++){
      String ne = words.get(i).get(NamedEntityTagAnnotation.class);
      if(tag == null) {
        tag = ne;
      } else if(! tag.equals(ne)){
        return null;
      }
    }
    
    return tag;
  }
}
