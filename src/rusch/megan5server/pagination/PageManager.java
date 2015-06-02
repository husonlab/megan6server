package rusch.megan5server.pagination;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

import megan.rma2.IReadBlockIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rusch.megan5client.connector.ReadBlockPage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;


/**Manages Pages which have been recently loaded or will be loaded soon.
 * 
 * In order to provide old pages we provide a cache with a short time memory
 * 
 * @author Hans-Joachim Ruscheweyh
 * 1:47:31 PM - Oct 28, 2014
 *
 */
public class PageManager {
	private Cache<String, ReadBlockPage> pageCode2readblocks = CacheBuilder.newBuilder().maximumSize(100).build();
	private Map<String, ReadBlockPaginator> pageCode2Paginators = new HashMap<String, ReadBlockPaginator>();
	private int blocksize = 50;
	private long timeout = 60000;
	private static final Logger logger = LoggerFactory.getLogger(PageManager.class);
	private Timer timer = new Timer(); //closing non active rma readers


	public PageManager(){
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				removeInactivePaginators();
			}
		}, 5*60*1000, 5*60*1000); // first time after 5 minutes and then every 5 minutes
	}
	
	
	/**Removing inactive paginators. A paginator is inactive after it has not been accessed for 1 minute.
	 * 
	 */
	private synchronized void removeInactivePaginators(){
		logger.info("Starting the timertask to remove inactive paginators.");
		List<String> pages2remove = new ArrayList<String>();
		for(Entry<String, ReadBlockPaginator> entry : pageCode2Paginators.entrySet()){
			if(!entry.getValue().isActive()){
				try {
					pages2remove.add(entry.getKey());
					entry.getValue().close();
					logger.info("Closing a Paginator because being inactive via the timer: " + entry.getValue().toString());
				} catch (IOException e) {
					logger.warn("Problems closing a Paginator.", e);
				}
			}
			for(String paginator : pages2remove){
				pageCode2Paginators.remove(paginator);
			}
		}
	}

	/**Register Paginator and return code for the first page
	 * 
	 * @param iterator
	 * @return
	 */
	public String registerPaginator(IReadBlockIterator iterator){
		ReadBlockPaginator paginator = new ReadBlockPaginator(iterator, timeout, blocksize);
		String initialPageId = paginator.retrieveInitialPageId();
		pageCode2Paginators.put(initialPageId, paginator);
		return initialPageId;
	}


	/**Retrieve next {@link ReadBlockPage} with pageId. Might happen that it is not present in the cache anymore. Then its not accessible anymore.
	 * 
	 * 
	 * 
	 * @param pageId
	 * @return
	 */
	public ReadBlockPage retrieveReadBlockPage(String pageId){
		if(pageId == null){
			return new ReadBlockPage();
		}
		ReadBlockPage page = pageCode2readblocks.getIfPresent(pageId);
		if(page == null){
			ReadBlockPaginator paginator = pageCode2Paginators.get(pageId);
			if(paginator == null){
				return new ReadBlockPage();
			}
			synchronized (paginator) {
				page = paginator.getNextPage();
				pageCode2Paginators.remove(pageId);
				pageCode2readblocks.put(pageId, page);
				if(page.getNextPageToken() != null)
					pageCode2Paginators.put(page.getNextPageToken(), paginator);
				else{
					try {
						paginator.close();
					} catch (IOException e) {
						logger.warn("Problems closing a Paginator.", e);
					}
				}
			}
		}
		return page;
	}




}