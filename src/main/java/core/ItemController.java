package core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.neo4j.graphdb.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.GraphDatabase;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.securitytoken.model.GetFederationTokenRequest;
import com.amazonaws.services.securitytoken.model.GetFederationTokenResult;

import core.mongodb.UserDocument;
import core.mongodb.UserDocumentRepository;
import core.mysql.Image;
import core.mysql.ImageRepository;
import core.mysql.User;
import core.mysql.UserRepository;
import core.neo4j.ItemRelationship;
import core.neo4j.ItemRelationshipRepository;
import core.neo4j.SubscribedRelationship;
import core.neo4j.SubscribedRelationshipRepository;
import core.neo4j.UserNode;
import core.neo4j.UserNodeRepository;

@RestController
public class ItemController {
	@Autowired
	UserRepository userRepo;
	@Autowired
	UserNodeRepository userNodeRepo;
	@Autowired
	ItemRelationshipRepository itemRelationshipRepo;
	@Autowired
	SubscribedRelationshipRepository subscribedRelationshipRepo;
	@Autowired
	GraphDatabase graphDatabase;
	@Autowired
	Neo4jTemplate neo4jTemplate;
	@Autowired
	UserDocumentRepository userDocumentRepo;
	@Autowired
	ImageRepository imageRepo;
	@Value("${awsbucketname}")
	private String awsBucketName;
	@Value("${awsstsendpoint}")
	private String awsSTSEndpoint;
	@Value("${awsfederateduserpolicy}")
	private String awsFederatedUserPolicy;

	@RequestMapping(value="/getitems")
	public QueueResp getS3Credentials(@RequestHeader(value="Authorization") String token){
		User user = userRepo.findByAccessToken(token.substring("Bearer ".length()));
		String name = user.getUsername();
		UserDocument userDocument = userDocumentRepo.findByName(name);
		List<String> queue = userDocument.getQueue();
		if (queue == null || queue.isEmpty()) {
			List<String> newItems = new ArrayList<String>();
			Random r = new Random(System.currentTimeMillis());
			int startId = r.nextInt(((int)imageRepo.count()))-10;
			List<Image> listOfImages = imageRepo.findByIdBetween(startId, startId+9);
			for (Image image: listOfImages){
				newItems.add(image.getHash());
			}
			userDocument.enQueueAll(newItems);
			userDocumentRepo.save(userDocument);
			queue = newItems;
		}
		return new QueueResp(queue);
	}
	
	@RequestMapping(value="/gets3credentials", method=RequestMethod.GET)
	public Credentials getItems(@RequestHeader(value="Authorization") String token){
		User user = userRepo.findByAccessToken(token.substring("Bearer ".length()));
		String name = user.getUsername();
		AWSSecurityTokenServiceClient stsClient = new AWSSecurityTokenServiceClient();
		stsClient.setEndpoint(awsSTSEndpoint);
		GetFederationTokenRequest federationTokenRequest = new GetFederationTokenRequest();
		federationTokenRequest.setDurationSeconds(7200);
		federationTokenRequest.setPolicy(awsFederatedUserPolicy);
		federationTokenRequest.setName(name);
		GetFederationTokenResult federationTokenResult = stsClient.getFederationToken(federationTokenRequest);
		Credentials federationTokenCredentials = federationTokenResult.getCredentials();
		return federationTokenCredentials;
	}
	
	
	@RequestMapping(value="/broadcastitem", method=RequestMethod.GET)
	public String broadcastItem(@RequestHeader(value="Authorization") String token, @RequestHeader(value="Item") String item){
		User user = userRepo.findByAccessToken(token.substring("Bearer ".length()));
		String name = user.getUsername();
		
		Integer like = 1;
		List<String> broadcastNames = null;
		
		
		Transaction tx = graphDatabase.beginTx();
		try {
			UserNode userNode = userNodeRepo.findByName(name);
			
			Set<SubscribedRelationship> subscribedRelationships = subscribedRelationshipRepo.findByUserNodeId(userNode.id);
			broadcastNames = new ArrayList<String>();
			for (SubscribedRelationship subscribedRelationship: subscribedRelationships){
				if (subscribedRelationship.startNode.id.equals(userNode.id)){
					broadcastNames.add(subscribedRelationship.endNode.name);
				} else {
					broadcastNames.add(subscribedRelationship.startNode.name);
				}
			}
			
			
			Set<ItemRelationship> itemRelationships = itemRelationshipRepo.findByEndNodeIdAndItemName(userNode.id, item);
//			subscribedRelationshipRepo.save(subscribedRelationships);
			List<SubscribedRelationship> updatedSubscribedRelationships = new ArrayList<SubscribedRelationship>();
			List<ItemRelationship> newItemRelationships = new ArrayList<ItemRelationship>();
			List<UserNode> itemBroadcastNodes = new ArrayList<UserNode>();
			for (SubscribedRelationship subscribedRelationship: subscribedRelationships){
				if (subscribedRelationship.startNode.id.equals(userNode.id)){
					itemBroadcastNodes.add(subscribedRelationship.endNode);
				} else {
					itemBroadcastNodes.add(subscribedRelationship.startNode);
				}
			}


			List<UserNode> deleteBroadcastNodes = new ArrayList<UserNode>();
			for (UserNode itemBroadcastNode : itemBroadcastNodes){
				for (ItemRelationship itemRelationship: itemRelationships){
					if (itemRelationship.startNode.id.equals(itemBroadcastNode.id)){
						deleteBroadcastNodes.add(itemBroadcastNode);
					}
				}
			}
			
			itemBroadcastNodes.removeAll(deleteBroadcastNodes);


			for (ItemRelationship itemRelationship: itemRelationships){
					if (like == 1 && itemRelationship.like == 1){
						UserNode otherNode = itemRelationship.startNode;
						for (SubscribedRelationship subscribedRelationship: subscribedRelationships){
							if (subscribedRelationship.startNode.id.equals(otherNode.id)){
								subscribedRelationship.setScore(subscribedRelationship.getScore() + 1);
								updatedSubscribedRelationships.add(subscribedRelationship);
							} else if (subscribedRelationship.endNode.id.equals(otherNode.id)){
								subscribedRelationship.setScore(subscribedRelationship.getScore() + 1);
								updatedSubscribedRelationships.add(subscribedRelationship);
							}
						}
					}
			}
			
			
			
			for (UserNode itemBroadcastNode: itemBroadcastNodes){
				ItemRelationship itemRelationship = neo4jTemplate.createRelationshipBetween(userNode, itemBroadcastNode, ItemRelationship.class, "ITEM", true);
				itemRelationship.setItemName(item);
				itemRelationship.setLike(like);
				newItemRelationships.add(itemRelationship);
			}
			
			
			List<UserDocument> usersToQueue = userDocumentRepo.findByNameIn(broadcastNames);
			for (UserDocument userDocument: usersToQueue){
				userDocument.enQueue(item);
			}
			
			itemRelationshipRepo.delete(itemRelationships);
			itemRelationshipRepo.save(newItemRelationships);
			subscribedRelationshipRepo.save(updatedSubscribedRelationships);
			userDocumentRepo.save(usersToQueue);
			
			tx.success();
		} finally{
			tx.close();
		}
		
		return "success";
	}
	
	
	@Transactional
	@RequestMapping(value="/pushitem", method=RequestMethod.GET)
	public String pushItem(@RequestHeader(value="Authorization") String token, @RequestHeader(value="Item") String item, @RequestHeader(value="Like") Integer like){
		User user = userRepo.findByAccessToken(token.substring("Bearer ".length()));
		String name = user.getUsername();
		UserDocument userDocument = userDocumentRepo.findByName(name);
		String serverItem = userDocument.deQueue();
		if (!serverItem.equals(item)){
			return "failure";
		}
		
		Transaction tx = graphDatabase.beginTx();
		try {
			UserNode userNode = userNodeRepo.findByName(name);
			
			Set<ItemRelationship> itemRelationships = itemRelationshipRepo.findByEndNodeIdAndItemName(userNode.id, item);
			Set<SubscribedRelationship> subscribedRelationships = subscribedRelationshipRepo.findByUserNodeId(userNode.id);
//			subscribedRelationshipRepo.save(subscribedRelationships);
			List<SubscribedRelationship> updatedSubscribedRelationships = new ArrayList<SubscribedRelationship>();
			List<ItemRelationship> newItemRelationships = new ArrayList<ItemRelationship>();
			List<UserNode> itemBroadcastNodes = new ArrayList<UserNode>();
			for (SubscribedRelationship subscribedRelationship: subscribedRelationships){
				if (subscribedRelationship.startNode.id.equals(userNode.id)){
					itemBroadcastNodes.add(subscribedRelationship.endNode);
				} else {
					itemBroadcastNodes.add(subscribedRelationship.startNode);
				}
			}


			List<UserNode> deleteBroadcastNodes = new ArrayList<UserNode>();
			for (UserNode itemBroadcastNode : itemBroadcastNodes){
				for (ItemRelationship itemRelationship: itemRelationships){
					if (itemRelationship.startNode.id.equals(itemBroadcastNode.id)){
						deleteBroadcastNodes.add(itemBroadcastNode);
					}
				}
			}
			
			itemBroadcastNodes.removeAll(deleteBroadcastNodes);


			for (ItemRelationship itemRelationship: itemRelationships){
					if (like == 1 && itemRelationship.like == 1){
						UserNode otherNode = itemRelationship.startNode;
						for (SubscribedRelationship subscribedRelationship: subscribedRelationships){
							if (subscribedRelationship.startNode.id.equals(otherNode.id)){
								subscribedRelationship.setScore(subscribedRelationship.getScore() + 1);
								updatedSubscribedRelationships.add(subscribedRelationship);
							} else if (subscribedRelationship.endNode.id.equals(otherNode.id)){
								subscribedRelationship.setScore(subscribedRelationship.getScore() + 1);
								updatedSubscribedRelationships.add(subscribedRelationship);
							}
						}
					}
			}
			
			
			
			for (UserNode itemBroadcastNode: itemBroadcastNodes){
				ItemRelationship itemRelationship = neo4jTemplate.createRelationshipBetween(userNode, itemBroadcastNode, ItemRelationship.class, "ITEM", true);
				itemRelationship.setItemName(item);
				itemRelationship.setLike(like);
				newItemRelationships.add(itemRelationship);
			}
			
			itemRelationshipRepo.delete(itemRelationships);
			itemRelationshipRepo.save(newItemRelationships);
			subscribedRelationshipRepo.save(updatedSubscribedRelationships);

			tx.success();
		} finally{
			tx.close();
		}
		
		userDocumentRepo.save(userDocument);
		return "success";
	}
}
