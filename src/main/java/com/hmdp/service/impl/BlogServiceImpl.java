package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogLikeDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        save(blog);
        // 推送到所有粉丝的 feed
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            String key = RedisConstants.FEED_KEY + follow.getUserId();
            redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE)
                    .add(System.currentTimeMillis(), blog.getId().toString());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(key);
        Double score = set.getScore(userId.toString());
        if (score == null) {
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                set.add(System.currentTimeMillis(), userId.toString());
            }
        } else {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                set.remove(userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(key);
        Collection<ScoredEntry<String>> entries = set.entryRange(0, 4);
        if (entries == null || entries.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<BlogLikeDTO> result = new ArrayList<>();
        for (ScoredEntry<String> entry : entries) {
            Long userId = Long.valueOf(entry.getValue());
            User user = userService.getById(userId);
            if (user == null) {
                continue;
            }
            BlogLikeDTO dto = new BlogLikeDTO();
            dto.setUserId(userId);
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            dto.setLikeTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.getScore().longValue()),
                    ZoneId.systemDefault()));
            result.add(dto);
        }
        return Result.ok(result);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        RScoredSortedSet<String> feedSet = redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE);

        // ZREVRANGE feed:{userId} offset offset+1 (按 rank 倒序取 2 条)
        Collection<ScoredEntry<String>> all = feedSet.entryRangeReversed(0, offset + 1);
        if (all == null || all.isEmpty()) {
            return Result.ok();
        }

        // 跳过前 offset 条，取最多 2 条
        List<ScoredEntry<String>> entries = new ArrayList<>();
        int i = 0;
        for (ScoredEntry<String> e : all) {
            if (i >= offset) entries.add(e);
            if (entries.size() >= 2) break;
            i++;
        }
        if (entries.isEmpty()) {
            return Result.ok();
        }

        List<Long> ids = new ArrayList<>(entries.size());
        long minTime = 0;
        int os = offset + entries.size();

        for (ScoredEntry<String> entry : entries) {
            String idStr = entry.getValue();
            ids.add(Long.valueOf(idStr));
            long time = entry.getScore().longValue();
            if (minTime == 0 || time < minTime) {
                minTime = time;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::isBlogLiked);
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(
                RedisConstants.BLOG_LIKED_KEY + blog.getId());
        blog.setIsLike(set.getScore(user.getId().toString()) != null);
    }
}
