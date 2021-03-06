package event.perceptron.featureGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import util.Span;
import util.TokenAnnotations;
import util.TypeConstraints;
import util.graph.DependencyGraph;
import util.graph.DependencyGraph.PathTerm;

import ace.acetypes.AceEntityMention;
import ace.acetypes.AceEventArgumentValue;
import ace.acetypes.AceMention;

import event.types.SentenceAssignment;
import event.types.SentenceInstance;

public class GlobalFeatureGenerator
{
	/**
	 * global features that about Trigger assignment in the current assn
	 * for example, bigram bag-of-word of current trigger and a previous trigger
	 * e.g. O O O Start_Position O O O End_Position, then the bigram can be "Start_Position, End_Position"
	 * @param inst
	 * @param index
	 * @param assn
	 * @param entityIndex
	 * @return
	 */
	static public List<String> get_global_features_triggers(SentenceInstance inst, int index, SentenceAssignment assn)
	{
		List<String> featureLine = new ArrayList<String>();
		String nodeLabel = assn.getLabelAtToken(index);
		
		List<String> preLabels = new ArrayList<String>();
		for(int j=0; j<index-1; j++)
		{
			// looking for previous triggers to make trigger-bigram
			String previousLabel = assn.getLabelAtToken(j);
			if(!previousLabel.equals(SentenceAssignment.Default_Trigger_Label))
			{
				if(!nodeLabel.equals(SentenceAssignment.Default_Trigger_Label))
				{
					// add to history
					if(!preLabels.contains(previousLabel))
					{
						preLabels.add(previousLabel);
					}
					String bigram = getSortedCombination(nodeLabel, previousLabel);
				
					// 2. are they in the same clause ?
					boolean sameClause = isSameClause(inst, index, j);
					featureLine.add("triggerPairSameClause=" + bigram + "#" + sameClause);
				
					// 3. dependency paths between two triggers
					String depPath = getDependencyPathsOfTriggers(inst, index, j);
					int distance = (depPath.split("#").length + 1) / 2;
					if(distance <= 3)
					{
						featureLine.add("triggerPairDepPath=" + bigram + "#" + depPath);
					}
				}
				// 4. check if the two trigger words are the same?
				String sameWordSameLabel = checkSameWordConsistency(inst, index, j, nodeLabel, previousLabel);
				if(sameWordSameLabel != null)
				{
					featureLine.add("SameWordSameLabel=" + sameWordSameLabel);
				}
			}
		}
		
		if(!nodeLabel.equals(SentenceAssignment.Default_Trigger_Label))
		{
			// 1. get bigram of trigger labels
			for(String previousLabel : preLabels)
			{
				String bigram = getSortedCombination(nodeLabel, previousLabel);
				featureLine.add("triggerPair=" + bigram);
			}
		}
		
		return featureLine;
	}
	
	/**
	 * check the same word consistency
	 * e.g. if there are two words "war" in the same sent, then their label should be consistent
	 * @param inst
	 * @param index
	 * @param j
	 * @return
	 */
	private static String checkSameWordConsistency(SentenceInstance inst,
			int index, int pre_index, String nodeLabel, String pre_nodeLabel)
	{
		List<Map<Class<?>, Object>> tokens = (List<Map<Class<?>, Object>>) inst.get(SentenceInstance.InstanceAnnotations.Token_FEATURE_MAPs);
		Map<Class<?>, Object> token = tokens.get(index);
		Map<Class<?>, Object> pre_token = tokens.get(pre_index);
		List<String> preWords = (List<String>) pre_token.get(TokenAnnotations.SynonymsAnnotation.class);
		List<String> words = (List<String>) token.get(TokenAnnotations.SynonymsAnnotation.class);		
		// use WordNet synonyms to evaluate similarity of two words
		if(preWords != null && words != null && !Collections.disjoint(preWords, words))
		{
			if(pre_nodeLabel.equals(nodeLabel))
			{
				return "true";
			}
			else
			{
				return "false";
			}
		}
		return null;
	}

	/**
	 * global features about the node with arguments, up to the entityIndex's entity
	 * @param inst
	 * @param index
	 * @param assn
	 * @param entityIndex
	 * @return
	 */
	static public List<String> get_global_features_node_level(SentenceInstance inst, int index, SentenceAssignment assn, int entityIndex)
	{
		List<String> featureLine = new ArrayList<String>();
		String nodeLabel = assn.getLabelAtToken(index);
		// skip if this node is not a trigger
		if(!SentenceAssignment.isArgumentable(nodeLabel))
		{
			return featureLine;
		}
		// skip if this node doesn't have edge assignment
		Map<Integer, Integer> edgeAssn = assn.getEdgeAssignment().get(index);
		if(edgeAssn == null)
		{
			return featureLine;
		}
		
		// feature 1 : two entity mentions share the same role in the same trigger
		List<String> twoEntitySameRole = twoMentionsSameRole(edgeAssn, inst, index, entityIndex);
		for(String feature : twoEntitySameRole)
		{
			feature = "sameRole:" + feature; 
			featureLine.add(feature);
		}
		
		// feature 2: one entity (two mentions) is two arguments
		// note: this is an negative feature, one entity can not be two arguments for the same event
		List<String> oneEntityTwoArgs = oneEntityTwoArgs(edgeAssn, inst, index, entityIndex, nodeLabel);
		for(String feature : oneEntityTwoArgs)
		{
			feature = "oneEntityTwoArgs:" + feature; 
			featureLine.add(feature);
		}
		
		// feature 3: if one entity is the other one's head modifier
		// e.g. the new appointed IBM CEO, "IBM" is a modifier of "CEO", and "CEO" is an PER, and "IBM" is Entity for "appointed"
		List<String> entityOverlaps = entityOverlapsInArgs(edgeAssn, inst, index, entityIndex, nodeLabel);
		for(String feature : entityOverlaps)
		{
			feature = "entitiesOverlap:" + feature; 
			featureLine.add(feature);
		}
		
		// feature 4: similar to feature 1 but consider relevant but different roles
//		List<String> twoEntityRelRole = twoMentionsRelatedRoles(edgeAssn, inst, index, entityIndex);
//		for(String feature : twoEntityRelRole)
//		{
//			feature = "relevantRoles:" + feature; 
//			featureLine.add(feature);
//		}
		
		return featureLine;
	}
	
	private static List<String> entityOverlapsInArgs(Map<Integer, Integer> edgeAssn, SentenceInstance inst, int index,
			int entityIndex, String nodeLabel)
	{
		List<String> ret = new ArrayList<String>();
		Integer edgeLabelIndex1 = edgeAssn.get(entityIndex);
		if(edgeLabelIndex1 == null)
		{
			return ret;
		}
		String edgeLabel1 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex1);
		if(edgeLabel1.equals(SentenceAssignment.Default_Argument_Label))
		{
			return ret;
		}
		for(int entity2 = entityIndex-1; entity2 >= 0; entity2--)
		{
			Integer edgeLabelIndex2 = edgeAssn.get(entity2);
			if(edgeLabelIndex2 == null)
			{
				continue;
			}
			String edgeLabel2 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex2);
			
			// Palestinian prime minister 
			AceMention mention2 = inst.eventArgCandidates.get(entity2);
			AceMention mention1 = inst.eventArgCandidates.get(entityIndex);
			if(mention1 instanceof AceEntityMention && mention2 instanceof AceEntityMention
					&& !mention1.getParent().equals(mention2.getParent()))
			{
				Span extent1 = mention1.extent;
				Span extent2 = mention2.extent;
				Span head1 = ((AceEntityMention) mention1).head;
				Span head2 = ((AceEntityMention) mention2).head;
				if(head2.within(extent1))
				{
					String feature = "modifier=" + edgeLabel2 + "#" + "head=" + edgeLabel1;
					ret.add(feature);
				}
				if(head1.within(extent2))
				{
					String feature = "tail=" + edgeLabel2 + "#" + "head=" + edgeLabel1;
					ret.add(feature);
				}
			}
			
			// co-chief executive of Vivendi Universal Entertainment 
			if(mention1 instanceof AceEntityMention && mention2.getType().equals("Job-Title"))
			{
				Span extent = mention2.extent;
				Span headOrigin = ((AceEntityMention) mention1).head;
				if(headOrigin.within(extent))
				{
					if(headOrigin.within(extent))
					{
						String feature = "Title=" + edgeLabel2 + "#" + mention1.getType() + "=" + edgeLabel1;
						ret.add(feature);
					}
				}
			}
		}
		return ret;
	}

	/**
	 * check features that one entity are two arguments for the same trigger
	 * @param edgeAssn
	 * @param inst
	 * @param index
	 * @param entityIndex
	 * @param nodeLabel 
	 * @return
	 */
	private static List<String> oneEntityTwoArgs(
			Map<Integer, Integer> edgeAssn, SentenceInstance inst, int index,
			int entityIndex, String nodeLabel)
	{
		List<String> ret = new ArrayList<String>();
		Integer edgeLabelIndex1 = edgeAssn.get(entityIndex);
		if(edgeLabelIndex1 == null)
		{
			return ret;
		}
		String edgeLabel1 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex1);
		if(edgeLabel1.equals(SentenceAssignment.Default_Argument_Label))
		{
			return ret;
		}
		for(int entity2 = entityIndex-1; entity2 >= 0; entity2--)
		{
			// same entity
			AceEventArgumentValue parent2 = inst.eventArgCandidates.get(entity2).getParent();
			AceEventArgumentValue parent1 = inst.eventArgCandidates.get(entityIndex).getParent();
			if(parent1 != null && parent1.equals(parent2))
			{
				Integer edgeLabelIndex2 = edgeAssn.get(entity2);
				if(edgeLabelIndex2 == null)
				{
					continue;
				}
				String edgeLabel2 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex2);
				if(edgeLabel2.equals(SentenceAssignment.Default_Argument_Label))
				{
					continue;
				}
				// fill feature 
				ret.add(nodeLabel);
			}
		}
		return ret;
	}

	/**
	 * given an assignment with completed node index, then compute the globle features
	 * on node index. An example of this kind of features is: check how many places argument in this node
	 * @param problem
	 * @param index
	 * @param sentenceAssignment
	 */
	static public List<String> get_global_features_node_level_omplete(SentenceInstance inst, int index, SentenceAssignment assn)
	{
		List<String> featureLine = new ArrayList<String>();
		String nodeLabel = assn.getLabelAtToken(index);
		// skip if this node is not a trigger
		if(!SentenceAssignment.isArgumentable(nodeLabel))
		{
			return featureLine;
		}
		// skip if this node doesn't have edge assignment
		Map<Integer, Integer> edgeAssn = assn.getEdgeAssignment().get(index);
		if(edgeAssn == null)
		{
			return featureLine;
		}
		
		// feature 1 : count number of different roles associated with this node
		Map<String, Integer> numRoles = getNumOfArgRoles(edgeAssn, inst, nodeLabel);
		for(String role : numRoles.keySet())
		{
			int num = numRoles.get(role);
			// gourp numbers > 3 as one num
			if(num > 3)
			{
				num = 4;
			}
			String feature = "roleNum:" + nodeLabel + "#" + role + "#" + num;
			featureLine.add(feature);
		}
		
		// feature 2: for mutilpe time arguments
		List<String> timeArgs = getTimeArgsBigram(edgeAssn, inst, nodeLabel);
		for(String time : timeArgs)
		{
			String feature = "timeArgPair=" + time;
			featureLine.add(feature);
		}
		
		return featureLine;
	}

	private static List<String> getTimeArgsBigram(Map<Integer, Integer> edgeAssn, SentenceInstance inst,
			String nodeLabel)
	{
		List<String> timeArgs = new ArrayList<String>();
		for(int entityIndex : edgeAssn.keySet())
		{
			int edgeLabelIndex = edgeAssn.get(entityIndex);
			String edgeLabel = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex);
			if(edgeLabel.equals(SentenceAssignment.Default_Argument_Label))
			{
				continue;
			}
			
			if(edgeLabel.startsWith("Time"))
			{
				if(!timeArgs.contains(edgeLabel))
				{
					timeArgs.add(edgeLabel);
				}
			}
		}
		
		List<String> ret = new ArrayList<String>();
		for(int i=0; i<timeArgs.size()-1; i++)
		{
			String time1 = timeArgs.get(i);
			for(int j=i+1; j<timeArgs.size(); j++)
			{
				String time2 = timeArgs.get(j);
				String pair = getSortedCombination(time1, time2);
				ret.add(pair);
			}
		}
		
		return ret;
	}

	/**
	 * get the features of two "entities share the same role in one trigger"
	 * @param edgeAssn
	 * @param inst
	 * @return
	 */
	private static List<String> twoMentionsSameRole(Map<Integer, Integer> edgeAssn, SentenceInstance inst, int triggerIndex, int entityIndex)
	{
		List<String> ret = new ArrayList<String>();
		Integer edgeLabelIndex1 = edgeAssn.get(entityIndex);
		if(edgeLabelIndex1 == null)
		{
			return ret;
		}
		String edgeLabel1 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex1);
		if(edgeLabel1.equals(SentenceAssignment.Default_Argument_Label))
		{
			return ret;
		}
		for(int entity2 = entityIndex-1; entity2 >= 0; entity2--)
		{
			Integer edgeLabelIndex2 = edgeAssn.get(entity2);
			if(edgeLabelIndex2 == null)
			{
				continue;
			}
			String edgeLabel2 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex2);
			if(edgeLabel2.equals(edgeLabel1))
			{
				AceMention mention1 = inst.eventArgCandidates.get(entityIndex);
				AceMention mention2 = inst.eventArgCandidates.get(entity2);
				// get features for entity1 and entity2
				// 1. whether they are co-referenced
				AceEventArgumentValue parent1 = mention1.getParent();
				AceEventArgumentValue parent2 = mention2.getParent();
				if(parent1 != null && parent2 != null && parent1.equals(parent2))
				{
					ret.add(edgeLabel2 + "#" + "coreference");
				}
				
				// 2. check if the extent of the two entities are overlapped
				if(mention1.extent.overlap(mention2.extent))
				{
					ret.add(edgeLabel2 + "#" + "overlapped");
				}
				
				// 3. check the tokens between the two entities in surface text. 
				// e.g. "A and B were killed", the token "and" is important
				// e.g. "kill thousands of innocent children, women and men"
				Vector<Integer> extentIndices1 = mention1.getExtentIndices();
				Vector<Integer> extentIndices2 = mention2.getExtentIndices();
				int minIndex = extentIndices1.get(extentIndices1.size() - 1);
				int maxIndex = extentIndices2.get(0);
				if(minIndex > maxIndex)
				{
					minIndex = extentIndices2.get(extentIndices2.size() - 1);
					maxIndex = extentIndices1.get(0);
				}
				if(minIndex < maxIndex && (maxIndex - minIndex) < 4)
				{
					for(int index = minIndex + 1; index < maxIndex; index++)
					{
						String text = (String) inst.getTokenFeatureMaps().get(index).get(TokenAnnotations.TextAnnotation.class);
						ret.add(edgeLabel2 + "#" + "between:" + text);
					}
				}
				
				// 4. check the dependency between the two entities 
				Vector<Integer> headIndices1 = mention1.getHeadIndices();
				Vector<Integer> headIndices2 = mention2.getHeadIndices();
				List<String> depPaths = getDependencyPaths(inst, headIndices1, headIndices2, triggerIndex);
				for(String path : depPaths)
				{
					int distance = (path.split("#").length + 1) / 2;
					if(distance <= 3)
					{
						ret.add(edgeLabel2 + "#" + "depPath:" + path);
					}
				}
			}
		}
		return ret;
	}

	/**
	 * get the features of two "entities play related roles in one trigger"
	 * e.g. one entity is Attacker, and the other one is Target
	 * @param edgeAssn
	 * @param inst
	 * @return
	 */
	private static List<String> twoMentionsRelatedRoles(Map<Integer, Integer> edgeAssn, SentenceInstance inst, int triggerIndex, int entityIndex)
	{
		List<String> ret = new ArrayList<String>();
		Integer edgeLabelIndex1 = edgeAssn.get(entityIndex);
		if(edgeLabelIndex1 == null)
		{
			return ret;
		}
		String edgeLabel1 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex1);
		if(edgeLabel1.equals(SentenceAssignment.Default_Argument_Label))
		{
			return ret;
		}
		for(int entity2 = entityIndex-1; entity2 >= 0; entity2--)
		{
			Integer edgeLabelIndex2 = edgeAssn.get(entity2);
			if(edgeLabelIndex2 == null)
			{
				continue;
			}
			String edgeLabel2 = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex2);
			
			// if two roles are some how relevant
			if(relevantRoles(edgeLabel1, edgeLabel2))
			{
				AceMention mention1 = inst.eventArgCandidates.get(entityIndex);
				AceMention mention2 = inst.eventArgCandidates.get(entity2);
				String roles = getSortedCombination(edgeLabel1, edgeLabel2);
				
				// 1. check if the extent of the two entities are overlapped
				if(mention1.extent.overlap(mention2.extent))
				{
					ret.add(roles + "#" + "overlapped");
				}
				
				// 2. check the tokens between the two entities in surface text. 
				// e.g. "A and B were killed", the token "and" is important
				// e.g. "kill thousands of innocent children, women and men"
				Vector<Integer> extentIndices1 = mention1.getExtentIndices();
				Vector<Integer> extentIndices2 = mention2.getExtentIndices();
				int minIndex = extentIndices1.get(extentIndices1.size() - 1);
				int maxIndex = extentIndices2.get(0);
				if(minIndex > maxIndex)
				{
					minIndex = extentIndices2.get(extentIndices2.size() - 1);
					maxIndex = extentIndices1.get(0);
				}
				if(minIndex < maxIndex && (maxIndex - minIndex) < 4)
				{
					for(int index = minIndex + 1; index < maxIndex; index++)
					{
						String text = (String) inst.getTokenFeatureMaps().get(index).get(TokenAnnotations.TextAnnotation.class);
						ret.add(roles + "#" + "between:" + text);
					}
				}
				
				// 3. check the dependency between the two entities 
				Vector<Integer> headIndices1 = mention1.getHeadIndices();
				Vector<Integer> headIndices2 = mention2.getHeadIndices();
				List<String> depPaths = getDependencyPaths(inst, headIndices1, headIndices2, triggerIndex);
				for(String path : depPaths)
				{
					int distance = (path.split("#").length + 1) / 2;
					
					if(distance <= 3)
					{
						ret.add(roles + "#" + "depPath:" + path);
					}
				}	
			}
		}
		return ret;
	}
	
	static String[][] relevantRoles = new String[][]{{"Attacker", "Target"}
		, {"Victim", "Agent"}, {"Agent", "Artifact"} 
		}; 
	
	private static boolean relevantRoles(String edgeLabel1, String edgeLabel2)
	{
		for(String[] pair : relevantRoles)
		{
			if(edgeLabel1.equals(pair[0]) && edgeLabel2.equals(pair[1]) 
					|| edgeLabel1.equals(pair[1]) && edgeLabel2.equals(pair[0]))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * count number of different roles in this node's edge assingnment
	 * @param edgeAssn
	 * @param nodeLabel 
	 * @return
	 */
	private static Map<String, Integer> getNumOfArgRoles(Map<Integer, Integer> edgeAssn, SentenceInstance inst, String nodeLabel)
	{	
		Map<String, Integer> ret = new HashMap<String, Integer>();
		Set<String> possibleRoles = TypeConstraints.argumentRoles.get(nodeLabel);
		for(String role : possibleRoles)
		{
			ret.put(role, 0);
		}
		
		for(int entityIndex : edgeAssn.keySet())
		{
			int edgeLabelIndex = edgeAssn.get(entityIndex);
			String edgeLabel = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(edgeLabelIndex);
			if(edgeLabel.equals(SentenceAssignment.Default_Argument_Label))
			{
				continue;
			}
			
			if(edgeLabel.startsWith("Time"))
			{
				edgeLabel = "Time";
			}
			
			Integer freq = ret.get(edgeLabel);
			if(freq == null)
			{
				freq = 0;
			}
			freq++;
			ret.put(edgeLabel, freq);
		}
		return ret;
	}
	
	
	/**
	 * get the shortest dependency paths between two entities
	 * @param sent
	 * @param headIndices1
	 * @return
	 */
	private static List<String> getDependencyPaths(SentenceInstance inst, Vector<Integer> headIndices1, Vector<Integer> headIndices2, int triggerIndex)
	{
		List<Map<Class<?>, Object>> tokens = (List<Map<Class<?>, Object>>) inst.get(SentenceInstance.InstanceAnnotations.Token_FEATURE_MAPs);
		DependencyGraph graph = (DependencyGraph) inst.get(SentenceInstance.InstanceAnnotations.DepGraph);
		if(graph == null)
		{
			return null;
		}
		Vector<PathTerm> path = graph.getShortestPathFeatured(headIndices1, headIndices2);
		if(path == null)
		{
			return null;
		}
		List<String> ret = new ArrayList<String>();
		// path from Entity Head (exclusively) to Trigger (exclusively)
		String path1 = "";
		for(int index = 1; index < path.size() - 1; index++)
		{
			PathTerm term = path.get(index);
			// if the term is a token, then try to use entity type to normalize it
			if(term.isVertex)
			{
				int token_index = term.vertex.index;
				String word = "";
				if(triggerIndex == token_index)
				{
					word = "TriggerWord";
				}
				else
				{
					word = (String) tokens.get(token_index).get(TokenAnnotations.PartOfSpeechAnnotation.class);
				}
				path1 += word + "#";
			}
			else
			{
				path1 += term.toString() + "#";
			}
		}
		ret.add(path1);
		return ret;
	}
	
	private static String getDependencyPathsOfTriggers(SentenceInstance inst, int trigger1, int trigger2)
	{
		List<Map<Class<?>, Object>> tokens = (List<Map<Class<?>, Object>>) inst.get(SentenceInstance.InstanceAnnotations.Token_FEATURE_MAPs);
		DependencyGraph graph = (DependencyGraph) inst.get(SentenceInstance.InstanceAnnotations.DepGraph);
		if(graph == null)
		{
			return null;
		}
		Vector<PathTerm> depPath = graph.getShortestPathFeatured(trigger1, trigger2);
		if(depPath == null)
		{
			return null;
		}
		// path from Entity Head (exclusively) to Trigger (exclusively)
		String path1 = "";
		for(int index = 1; index < depPath.size() - 1; index++)
		{
			PathTerm term = depPath.get(index);
			// if the term is a token, then try to use entity type to normalize it
			if(term.isVertex)
			{
				int token_index = term.vertex.index;
				String word = (String) tokens.get(token_index).get(TokenAnnotations.PartOfSpeechAnnotation.class);
				path1 += word + "#";
			}
			else
			{
				path1 += term.toString() + "#";
			}
		}
		return path1;
	}
	
	/**
	 * features involves previous node and current node. 
	 * e.g. if there is one entity mention is arguments of current node and one of previous nodes, 
	 * then compute some features of this tri-angle structure
	 * @param inst
	 * @param index
	 * @param assn
	 * @return
	 */
	static public List<String> get_global_features_sent_level(SentenceInstance inst, int index, SentenceAssignment assn, int entityIndex)
	{
		List<String> featureLine = new ArrayList<String>();
		String node_label = assn.getLabelAtToken(index);
		if(node_label.equals(SentenceAssignment.Default_Trigger_Label))
		{
			return featureLine;
		}
		Map<Integer, Integer> edgeAssn = assn.getEdgeAssignment().get(index);
		if(edgeAssn == null)
		{
			return featureLine;
		}
		Integer role_index = edgeAssn.get(entityIndex);
		if(role_index == null)
		{
			return featureLine;
		}
		String role = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(role_index);
		if(role.equals(SentenceAssignment.Default_Argument_Label))
		{
			return featureLine;
		}
		
		// check previous nodes
		for(int index_pre=0; index_pre<index; index_pre++)
		{
			String node_label_pre = assn.getLabelAtToken(index_pre);
			if(SentenceAssignment.isArgumentable(node_label_pre))
			{
				Map<Integer, Integer> edgeAssn_pre = assn.getEdgeAssignment().get(index_pre);
				if(edgeAssn_pre != null)
				{
					// find mention level common arguments (the two args may or may not have same role)
					Integer role_index_pre = edgeAssn_pre.get(entityIndex);
					if(role_index_pre == null)
					{
						continue;
					}
					String role_pre = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(role_index_pre);
					
					if(role.equals(SentenceAssignment.Default_Argument_Label) || 
							role_pre.equals(SentenceAssignment.Default_Argument_Label))
					{
						continue;
					}
					// 1. the trigger types
					String feature = "same_mention_triggers:" + getSortedCombination(node_label, node_label_pre);
					featureLine.add(feature);
					
					// 2. the roles with node Label
					// the reason for adding node_label is that some roles 
					// like "Person" has different meaning for different events
					feature = "same_mention_roles:" + getSortedCombination(role + node_label, role_pre + node_label_pre);
					featureLine.add(feature);
					
					// 3. the dependency between two triggers
					feature = "same_mention_dep:" + getDependencyPathsOfTriggers(inst, index, index_pre);
					featureLine.add(feature);
					
					// find entity level common arguments
					AceMention mention = inst.eventArgCandidates.get(entityIndex);
					for(int j=0; j<inst.eventArgCandidates.size(); j++)
					{
						if(j == entityIndex)
						{
							continue;
						}
						AceMention mention2 = inst.eventArgCandidates.get(j);
						if(mention.getParent() != null && mention2.getParent() != null 
								&& mention.getParent().equals(mention2.getParent()))
						{
							role_index_pre = edgeAssn_pre.get(j);
							if(role_index_pre == null)
							{
								continue;
							}
							role_pre = (String) inst.alphabets.edgeTargetAlphabet.lookupObject(role_index_pre);
							if(role_pre.equals(SentenceAssignment.Default_Argument_Label))
							{
								continue;
							}
							// 1. the trigger types
							feature = "same_entity_triggers:" + getSortedCombination(node_label, node_label_pre);
							featureLine.add(feature);
							
							// 2. the roles
							feature = "same_entity_roles:" + getSortedCombination(role+node_label, role_pre+node_label_pre);
							featureLine.add(feature);
						}
					}
				}
			}
		}
		return featureLine;
	}
	
	/**
	 * given a set of labels, get a string of ordered labels
	 * @param label1
	 * @param label2
	 * @return
	 */
	static private String getSortedCombination(String label1, String label2)
	{
		String ret = "";
		List<String> list = new ArrayList<String>();
		list.add(label1);
		list.add(label2);
		// sort
		Collections.sort(list);
		for(String label : list)
		{
			ret += label + "#";
		}
		return ret;
	}
	
	/**
	 * check if two triggers are in the same minimal clause
	 * @param headIndices
	 * @param tokens
	 * @param i
	 * @return
	 */
	private static Boolean isSameClause(SentenceInstance inst, int i, int j)
	{
		List<Map<Class<?>, Object>> tokens = inst.getTokenFeatureMaps();
		Integer clause_num_token = (Integer) tokens.get(i).get(TokenAnnotations.ClauseAnnotation.class);
		if(clause_num_token == null)
		{
			return false;
		}
		Integer clause_num = (Integer) tokens.get(j).get(TokenAnnotations.ClauseAnnotation.class);
		if(clause_num != null && clause_num_token.equals(clause_num))
		{
			return true;
		}
		return false;
	}
}
