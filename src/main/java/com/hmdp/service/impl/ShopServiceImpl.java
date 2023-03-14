package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;
    private static final ExecutorService CACHE_REBUILDER_EXECUTOR= Executors.newFixedThreadPool(10);
    private StringRedisTemplate stringRedisTemplate;
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    private void saveShop2Redis(Long id,Long expireSeconds){
        Shop shop=getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }



    @Override
    public Result queryById(Long id) {

        //缓存穿透
        // Shop shop=queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        //互斥锁解决缓存击穿
//        Shop shop=queryWithMutex(id);
//
        //逻辑过期时间
//        Shop shop=queryWithLogicalExpire(id);
//        if(shop==null){
//            return Result.fail("失败");
//        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicalExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){

            return  null;
        }
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        if(expireTime.isAfter((LocalDateTime.now()))){
            return shop;
        }
        String lockkey="lock:shop:"+id;
        boolean isLock=tryLock(lockkey);
        if(isLock){
            CACHE_REBUILDER_EXECUTOR.submit(()->{

                try {
                    this.saveShop2Redis(id,20l);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    unlock(lockkey);
                }

            });
        }

        return shop;
    }





    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return  shop;
        }
        if(shopJson!=null){
            return null;
        }
        //缓存重建
        String lockkey="lock:shop:"+id;
        Shop shop= null;
        try {
            boolean islock=tryLock(lockkey);
            if(!islock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            shop = getById(id);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally {
            unlock(lockkey);
        }
        return shop;
    }


    public Shop queryWithPassThrough(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return  shop;
        }
        if(shopJson!=null){
            return null;
        }
        Shop shop=getById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    @Override
    @Transactional
    public Result updata(Shop shop) {
        Long id =shop.getId();
        if(id==null){
            return Result.fail("id为空");
        }
        updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

}
