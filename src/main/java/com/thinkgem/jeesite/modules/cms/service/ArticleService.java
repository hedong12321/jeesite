/**
 * Copyright &copy; 2012-2016 <a href="https://github.com/thinkgem/jeesite">JeeSite</a> All rights reserved.
 */
package com.thinkgem.jeesite.modules.cms.service;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;
import com.thinkgem.jeesite.common.config.Global;
import com.thinkgem.jeesite.common.persistence.Page;
import com.thinkgem.jeesite.common.service.CrudService;
import com.thinkgem.jeesite.common.utils.CacheUtils;
import com.thinkgem.jeesite.common.utils.StringUtils;
import com.thinkgem.jeesite.modules.cms.dao.ArticleDao;
import com.thinkgem.jeesite.modules.cms.dao.ArticleDataDao;
import com.thinkgem.jeesite.modules.cms.dao.CategoryDao;
import com.thinkgem.jeesite.modules.cms.entity.Article;
import com.thinkgem.jeesite.modules.cms.entity.ArticleData;
import com.thinkgem.jeesite.modules.cms.entity.Category;
import com.thinkgem.jeesite.modules.sys.utils.UserUtils;
import org.springframework.util.CollectionUtils;
import org.wltea.analyzer.lucene.IKAnalyzer;

/**
 * 文章Service
 * @author ThinkGem
 * @version 2013-05-15
 */
@Service
@Transactional(readOnly = true)
public class ArticleService extends CrudService<ArticleDao, Article> {

	@Autowired
	private ArticleDataDao articleDataDao;
	@Autowired
	private CategoryDao categoryDao;
	
	@Transactional(readOnly = false)
	public Page<Article> findPage(Page<Article> page, Article article, boolean isDataScopeFilter) {
		// 更新过期的权重，间隔为“6”个小时
		Date updateExpiredWeightDate =  (Date)CacheUtils.get("updateExpiredWeightDateByArticle");
		if (updateExpiredWeightDate == null || (updateExpiredWeightDate != null 
				&& updateExpiredWeightDate.getTime() < new Date().getTime())){
			dao.updateExpiredWeight(article);
			CacheUtils.put("updateExpiredWeightDateByArticle", DateUtils.addHours(new Date(), 6));
		}
//		DetachedCriteria dc = dao.createDetachedCriteria();
//		dc.createAlias("category", "category");
//		dc.createAlias("category.site", "category.site");
		if (article.getCategory()!=null && StringUtils.isNotBlank(article.getCategory().getId()) && !Category.isRoot(article.getCategory().getId())){
			Category category = categoryDao.get(article.getCategory().getId());
			if (category==null){
				category = new Category();
			}
			category.setParentIds(category.getId());
			category.setSite(category.getSite());
			article.setCategory(category);
		}
		else{
			article.setCategory(new Category());
		}
//		if (StringUtils.isBlank(page.getOrderBy())){
//			page.setOrderBy("a.weight,a.update_date desc");
//		}
//		return dao.find(page, dc);
	//	article.getSqlMap().put("dsf", dataScopeFilter(article.getCurrentUser(), "o", "u"));
		return super.findPage(page, article);
		
	}

	@Transactional(readOnly = false)
	public void save(Article article) {
		if (article.getArticleData().getContent()!=null){
			article.getArticleData().setContent(StringEscapeUtils.unescapeHtml4(
					article.getArticleData().getContent()));
		}
		// 如果没有审核权限，则将当前内容改为待审核状态
		if (!UserUtils.getSubject().isPermitted("cms:article:audit")){
			article.setDelFlag(Article.DEL_FLAG_AUDIT);
		}
		// 如果栏目不需要审核，则将该内容设为发布状态
		if (article.getCategory()!=null&&StringUtils.isNotBlank(article.getCategory().getId())){
			Category category = categoryDao.get(article.getCategory().getId());
			if (!Global.YES.equals(category.getIsAudit())){
				article.setDelFlag(Article.DEL_FLAG_NORMAL);
			}
		}
		article.setUpdateBy(UserUtils.getUser());
		article.setUpdateDate(new Date());
        if (StringUtils.isNotBlank(article.getViewConfig())){
            article.setViewConfig(StringEscapeUtils.unescapeHtml4(article.getViewConfig()));
        }
        
        ArticleData articleData = new ArticleData();;
		if (StringUtils.isBlank(article.getId())){
			article.preInsert();
			articleData = article.getArticleData();
			articleData.setId(article.getId());
			dao.insert(article);
			articleDataDao.insert(articleData);
		}else{
			article.preUpdate();
			articleData = article.getArticleData();
			articleData.setId(article.getId());
			dao.update(article);
			articleDataDao.update(article.getArticleData());
		}
	}
	
	@Transactional(readOnly = false)
	public void delete(Article article, Boolean isRe) {
//		dao.updateDelFlag(id, isRe!=null&&isRe?Article.DEL_FLAG_NORMAL:Article.DEL_FLAG_DELETE);
		// 使用下面方法，以便更新索引。
		//Article article = dao.get(id);
		//article.setDelFlag(isRe!=null&&isRe?Article.DEL_FLAG_NORMAL:Article.DEL_FLAG_DELETE);
		//dao.insert(article);
		super.delete(article);
	}
	
	/**
	 * 通过编号获取内容标题
	 * @return new Object[]{栏目Id,文章Id,文章标题}
	 */
	public List<Object[]> findByIds(String ids) {
		if(ids == null){
			return new ArrayList<Object[]>();
		}
		List<Object[]> list = Lists.newArrayList();
		String[] idss = StringUtils.split(ids,",");
		Article e = null;
		for(int i=0;(idss.length-i)>0;i++){
			e = dao.get(idss[i]);
			list.add(new Object[]{e.getCategory().getId(),e.getId(),StringUtils.abbr(e.getTitle(),50)});
		}
		return list;
	}
	
	/**
	 * 点击数加一
	 */
	@Transactional(readOnly = false)
	public void updateHitsAddOne(String id) {
		dao.updateHitsAddOne(id);
	}
	
	/**
	 * 更新索引
	 */
	public void createIndex(){
		//dao.createIndex();

        // 查询所有文章，创建索引
		List<Article> allArticle = dao.findAllList(new Article());
		if (!CollectionUtils.isEmpty(allArticle)) {
		    try {
                Directory directory = FSDirectory.open(Paths.get("lucene_index_repository"));

                IndexWriterConfig config = new IndexWriterConfig(new IKAnalyzer());
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

                IndexWriter writer = new IndexWriter(directory, config);

                FieldType storeOnly = new FieldType();
                storeOnly.setIndexOptions(IndexOptions.NONE); // 是否索引
                storeOnly.setStored(true); // 是否存储
                storeOnly.setTokenized(false); // 是否分词

                Document doc = null;
                for (Article article: allArticle) {
                    doc = new Document();
                    doc.add(new Field("title", article.getTitle(), TextField.TYPE_STORED));
                    doc.add(new Field("keywords", article.getKeywords(), TextField.TYPE_STORED));
                    doc.add(new Field("description", StringUtils.isNotEmpty(article.getDescription()) ? article.getDescription() : "", TextField.TYPE_STORED));

					ArticleData articleData = articleDataDao.get(article.getId());
                    doc.add(new Field("articleData.content",
                            (articleData != null && StringUtils.isNotEmpty(articleData.getContent())) ? articleData.getContent() : "", TextField.TYPE_STORED));

                    doc.add(new Field("delFlag", article.getDelFlag(), storeOnly));
                    doc.add(new Field("category.ids", article.getCategory().getIds(), storeOnly));
                    doc.add(new Field("updateDate", com.thinkgem.jeesite.common.utils.DateUtils.formatDate(article.getUpdateDate(), "yyyy-MM-dd"), storeOnly));
                    doc.add(new Field("id", article.getId(), storeOnly));

                    writer.addDocument(doc);
                }

                writer.commit();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

		}
	}
	
	/**
	 * 全文检索
	 */
	//FIXME 暂不提供检索功能
	public Page<Article> search(Page<Article> page, String q, String categoryId, String beginDate, String endDate){
		
		// 设置查询条件
//		BooleanQuery query = dao.getFullTextQuery(q, "title","keywords","description","articleData.content");
//		
//		// 设置过滤条件
//		List<BooleanClause> bcList = Lists.newArrayList();
//
//		bcList.add(new BooleanClause(new TermQuery(new Term(Article.FIELD_DEL_FLAG, Article.DEL_FLAG_NORMAL)), Occur.MUST));
//		if (StringUtils.isNotBlank(categoryId)){
//			bcList.add(new BooleanClause(new TermQuery(new Term("category.ids", categoryId)), Occur.MUST));
//		}
//		
//		if (StringUtils.isNotBlank(beginDate) && StringUtils.isNotBlank(endDate)) {   
//			bcList.add(new BooleanClause(new TermRangeQuery("updateDate", beginDate.replaceAll("-", ""),
//					endDate.replaceAll("-", ""), true, true), Occur.MUST));
//		}   
		
		//BooleanQuery queryFilter = dao.getFullTextQuery((BooleanClause[])bcList.toArray(new BooleanClause[bcList.size()]));

//		System.out.println(queryFilter);
		
		// 设置排序（默认相识度排序）
		//FIXME 暂时不提供lucene检索
		//Sort sort = null;//new Sort(new SortField("updateDate", SortField.DOC, true));
		// 全文检索
		//dao.search(page, query, queryFilter, sort);
		// 关键字高亮
		//dao.keywordsHighlight(query, page.getList(), 30, "title");
		//dao.keywordsHighlight(query, page.getList(), 130, "description","articleData.content");

        try {
            Directory directory = FSDirectory.open(Paths.get("lucene_index_repository"));
            IndexReader reader = DirectoryReader.open(directory);
            IndexSearcher searcher = new IndexSearcher(reader);

            BooleanQuery query = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1)
                    .add(new BooleanClause(new TermQuery(new Term("title", q)), BooleanClause.Occur.SHOULD))
                    .add(new BooleanClause(new TermQuery(new Term("keywords", q)), BooleanClause.Occur.SHOULD))
                    .add(new BooleanClause(new TermQuery(new Term("description", q)), BooleanClause.Occur.SHOULD))
                    .add(new BooleanClause(new TermQuery(new Term("articleData.content", q)), BooleanClause.Occur.SHOULD))
                    .build();

            /*
            BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder()
                    .add(new BooleanClause(new TermQuery(new Term("delFlag", Article.DEL_FLAG_NORMAL)), BooleanClause.Occur.MUST));

            if (StringUtils.isNotBlank(categoryId)){
                filterBuilder.add(new BooleanClause(new TermQuery(new Term("category.ids", categoryId)), BooleanClause.Occur.MUST));
		    }

            if (StringUtils.isNotBlank(beginDate) && StringUtils.isNotBlank(endDate)) {
                filterBuilder.add(new BooleanClause(TermRangeQuery.newStringRange("updateDate", beginDate.replaceAll("-", ""),
					endDate.replaceAll("-", ""), true, true), BooleanClause.Occur.MUST));
		    }

            BooleanQuery filter = filterBuilder.build();
            */

            Sort sort = new Sort(new SortField("updateDate", SortField.Type.DOC, true));

            TopFieldCollector collector = TopFieldCollector.create(sort, page.getPageSize(), false, false, false, false);

            searcher.search(query, collector);

            TopDocs docs = collector.topDocs((page.getPageNo() -1) * page.getPageSize(), page.getPageNo() * page.getPageSize() - 1);
            if (docs != null && docs.totalHits > 0) {
                page.setCount(docs.totalHits);

                List<Article> articles = new ArrayList<>();
                Article article = null;
                ArticleData articleData = null;
                for (ScoreDoc scoreDoc : docs.scoreDocs) {
                    int docId = scoreDoc.doc;//文档编号
                    Document document = searcher.doc(docId);
                    article = new Article();
                    articleData = new ArticleData();
                    article.setId(document.get("id"));
                    article.setTitle(document.get("title"));
                    article.setKeywords(document.get("keywords"));
                    article.setDescription(document.get("description"));

                    articleData.setId(document.get("id"));
                    articleData.setContent(document.get("articleData.content"));

                    article.setArticleData(articleData);

                    articles.add(article);
                }

                page.setList(articles);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

		return page;
	}
	
}
