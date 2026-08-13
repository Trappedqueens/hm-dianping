package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private static final String FOLLOWS_KEY = "follows:";

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private IUserService userService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        RSet<String> set = redissonClient.getSet(FOLLOWS_KEY + userId, StringCodec.INSTANCE);
        if (BooleanUtil.isTrue(isFollow)) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
            set.add(followUserId.toString());
        } else {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            set.remove(followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        RSet<String> set = redissonClient.getSet(FOLLOWS_KEY + userId, StringCodec.INSTANCE);
        if (set.contains(followUserId.toString())) {
            return Result.ok(true);
        }
        int count = count(new QueryWrapper<Follow>()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId));
        if (count > 0) {
            set.add(followUserId.toString());
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result followCommons(Long targetUserId) {
        Long userId = UserHolder.getUser().getId();
        RSet<String> mySet = redissonClient.getSet(FOLLOWS_KEY + userId, StringCodec.INSTANCE);
        Set<String> intersect = mySet.readIntersection(FOLLOWS_KEY + targetUserId);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<UserDTO> result = new ArrayList<>();
        for (String idStr : intersect) {
            Long id = Long.valueOf(idStr);
            User user = userService.getById(id);
            if (user == null) {
                continue;
            }
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            result.add(dto);
        }
        return Result.ok(result);
    }
}
