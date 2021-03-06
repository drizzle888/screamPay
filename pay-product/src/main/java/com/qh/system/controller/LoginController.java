package com.qh.system.controller;


import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.qh.common.annotation.Log;
import com.qh.common.config.CfgKeyConst;
import com.qh.common.controller.BaseController;
import com.qh.common.domain.Tree;
import com.qh.common.utils.CacheUtils;
import com.qh.common.utils.R;
import com.qh.common.utils.ShiroUtils;
import com.qh.pay.api.utils.ParamUtil;
import com.qh.redis.service.RedisUtil;
import com.qh.sms.service.SMSService;
import com.qh.sms.service.SMSServiceFactory;
import com.qh.sms.service.impl.SMSServiceAliImpl;
import com.qh.sms.service.impl.SMSServiceTXImpl;
import com.qh.system.domain.MenuDO;
import com.qh.system.domain.UserDO;
import com.qh.system.service.MenuService;
import com.qh.system.service.SessionService;
import com.qh.system.service.UserService;

import javax.servlet.http.HttpSession;

@Controller
public class LoginController extends BaseController {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private MenuService menuService;

    @Autowired
    private UserService userService;
    
    @Autowired
	SessionService sessionService;
    @Autowired
    SMSServiceAliImpl smsServiceAli;
    @Autowired
    SMSServiceTXImpl smsServiceTx;

    @GetMapping({"/", ""})
    String welcome(Model model) {
        return "redirect:/index";
    }

    @Log("??????????????????")
    @GetMapping({"/index"})
    String index(Model model) {
        List<Tree<MenuDO>> menus = menuService.listMenuTree(getUserId());
        model.addAttribute("menus", menus);
        UserDO user = getUser();
        model.addAttribute("name", user.getName());
        UserDO dataUserDo = userService.get(user.getUserId());
        String funPass = dataUserDo.getFundPassword();
        model.addAttribute("firstLogin",StringUtils.isBlank(funPass)?"true":"false");
        model.addAttribute("passNoUp","1".equals(user.getPassword())?"true":"false");
        model.addAttribute("helpDom",RedisUtil.getValue("sys_config_upload_help_dom"));
        model.addAttribute("userType",user.getUserType());
        logger.info(getUser().getName());
        return "index_v1";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    /**
     * @param username ?????????
     * @return
     * @Description ???????????? ?????????
     */
    @PostMapping("/salt")
    @ResponseBody
    R salt(@RequestParam("username") String username) {
        int salt = ParamUtil.generateCode6();
        CacheUtils.setLoginSalt(username, String.valueOf(salt));
        return R.okData(salt);
    }

    @Log("??????")
    @PostMapping("/login")
    @ResponseBody
    R ajaxLogin(@RequestParam("username") String username, @RequestParam("password") String password, HttpSession session) {
    	
    	//?????????????????????????????????????????????
    	sessionService.forceLogoutByUserName(username);
        UsernamePasswordToken token = new UsernamePasswordToken(username, password);
        Subject subject = SecurityUtils.getSubject();
        try {
            subject.login(token);
            session.setAttribute("username",username);
            return R.ok();
        } catch (AuthenticationException e) {
            return R.error("?????????????????????");
        }
    }

    @Log("?????????????????????????????????")
    @PostMapping("/chgSendCode")
    @ResponseBody
    R chgSendCode(@RequestParam("codeType") int codeType) {
        UserDO userDO = getUser();
        if (userDO == null || ParamUtil.isEmpty(userDO.getName())) {
            return R.error("???????????????????????????");
        }
        try {
        	UserDO dataUserDo = userService.get(userDO.getUserId());
        	String phone = dataUserDo.getMobile();
        	String validCode = String.valueOf(ParamUtil.generateCode6());
        	String key = CfgKeyConst.sms_code_phone_update_pass+phone+"_"+codeType;
        	Long expireTime = RedisUtil.getRedisTemplate().getExpire(key);
        	if(expireTime > 0) {
        		return R.error("??????"+expireTime+"????????????!");
        	}
        	
        	String type = RedisUtil.getSMSConfigValue(CfgKeyConst.sms_send_type);
        	boolean isSuccess = false;
        	if(CfgKeyConst.sms_send_type_tx.equals(type))
        		isSuccess = smsServiceTx.sendMessageReg(phone, validCode);
        	else if(CfgKeyConst.sms_send_type_aliy.equals(type))
        		isSuccess = smsServiceAli.sendMessageReg(phone, validCode);
        	if(isSuccess){
        		RedisUtil.setValue(key, validCode);
        		RedisUtil.getRedisTemplate().expire(key, 60, TimeUnit.SECONDS);
        		return R.ok("????????????");
        	}else
        		return R.error("???????????????");
        } catch (AuthenticationException e) {
            return R.error("???????????????");
        }
    }
    
    @Log("????????????")
    @PostMapping("/chgPass")
    @ResponseBody
    R chgPass(@RequestParam("oldPassword") String oldPassword, @RequestParam("newPassword") String newPassword,@RequestParam("codeType") int codeType,@RequestParam("code") String code) {
        UserDO userDO = getUser();
        if (userDO == null || ParamUtil.isEmpty(userDO.getName())) {
            return R.error("???????????????????????????");
        }
        try {
            UserDO dataUserDo = userService.get(userDO.getUserId());
            /*String phone = dataUserDo.getMobile();
            if(StringUtils.isBlank(code)) {
            	return R.error("?????????????????????");
            }
            String key = CfgKeyConst.sms_code_phone_update_pass+phone+"_"+codeType;
            String orgCode = (String)RedisUtil.getValue(key);
            if(StringUtils.isBlank(orgCode)) {
            	return R.error("?????????????????????");
            }
        	Long expireTime = RedisUtil.getRedisTemplate().getExpire(key);
            if(expireTime <= 0) {
            	return R.error("?????????????????????");
            }
            if(!orgCode.equals(code)) {
            	return R.error("?????????????????????");
            }*/
        	
            if (dataUserDo != null && dataUserDo.getPassword().equals(oldPassword)) {
                dataUserDo.setPassword(newPassword);
                userService.updatePassword(dataUserDo);
                userDO.setPassword("1");
            } else {
                return R.error("??????????????????");
            }
//            RedisUtil.getRedisTemplate().delete(key);
            return R.ok("????????????????????????");
        } catch (AuthenticationException e) {
            return R.error("??????????????????");
        }
    }

    @Log("??????????????????")
    @PostMapping("/chgFundPass")
    @ResponseBody
    R chgFundPass(@RequestParam("oldFundPassword") String oldFundPassword, @RequestParam("newFundPassword") String newFundPassword,@RequestParam("codeType") int codeType,@RequestParam("code") String code) {
        UserDO userDO = getUser();
        if (userDO == null || ParamUtil.isEmpty(userDO.getName())) {
            return R.error("?????????????????????????????????");
        }
        try {
            UserDO dataUserDo = userService.get(userDO.getUserId());
            
            /*String phone = dataUserDo.getMobile();
            if(StringUtils.isBlank(code)) {
            	return R.error("?????????????????????");
            }
            String key = CfgKeyConst.sms_code_phone_update_pass+phone+"_"+codeType;
            String orgCode = (String)RedisUtil.getValue(key);
            if(StringUtils.isBlank(orgCode)) {
            	return R.error("?????????????????????");
            }
        	Long expireTime = RedisUtil.getRedisTemplate().getExpire(key);
            if(expireTime <= 0) {
            	return R.error("?????????????????????");
            }
            if(!orgCode.equals(code)) {
            	return R.error("?????????????????????");
            }*/
            
            if (dataUserDo != null) {
                if (ParamUtil.isEmpty(oldFundPassword)) {
                    dataUserDo.setFundPassword(newFundPassword);
                    userService.updateFundPassword(dataUserDo);
                } else if (oldFundPassword.equals(dataUserDo.getFundPassword())) {
                    dataUserDo.setFundPassword(newFundPassword);
                    userService.updateFundPassword(dataUserDo);
                } else {
                    throw new AuthenticationException("???????????????????????????");
                }
            } else {
                return R.error("????????????????????????");
            }
//            RedisUtil.getRedisTemplate().delete(key);
            return R.ok("???????????????????????????");
        } catch (AuthenticationException e) {
            return R.error("????????????????????????");
        }
    }

    /**
     * @Description ??????????????????????????????
     * @Author chensi
     * @Time 2017/12/21 11:31
     */
    @PostMapping("/hasFundPassword")
    @ResponseBody
    R hasFundPassword() {
        UserDO userDO = getUser();
        if (userDO == null || ParamUtil.isEmpty(userDO.getName())) {
            return R.error("?????????????????????????????????");
        }

        UserDO dataUserDo = userService.get(userDO.getUserId());
        if (dataUserDo != null && ParamUtil.isNotEmpty(dataUserDo.getFundPassword())) {
            return R.ok();
        }
        return R.error();
    }


    @GetMapping("/logout")
    String logout() {
        ShiroUtils.logout();
        return "redirect:/login";
    }

    @GetMapping("/main")
    String main() {
        return "main";
    }

    @GetMapping("/403")
    String error403() {
        return "403";
    }

}
