/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Kamax Sarl
 *
 * https://www.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.threepid.notification.email;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import io.kamax.matrix.ThreePid;
import io.kamax.matrix.ThreePidMedium;
import io.kamax.mxisd.config.MxisdConfig;
import io.kamax.mxisd.config.threepid.connector.EmailSendGridConfig;
import io.kamax.mxisd.exception.FeatureNotAvailable;
import io.kamax.mxisd.invitation.IMatrixIdInvite;
import io.kamax.mxisd.invitation.IThreePidInviteReply;
import io.kamax.mxisd.notification.NotificationHandler;
import io.kamax.mxisd.threepid.generator.PlaceholderNotificationGenerator;
import io.kamax.mxisd.threepid.session.IThreePidSession;
import io.kamax.mxisd.util.FileUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static io.kamax.mxisd.config.threepid.connector.EmailSendGridConfig.EmailTemplate;

public class EmailSendGridNotificationHandler extends PlaceholderNotificationGenerator implements NotificationHandler {

    public static final String ID = "sendgrid";

    private transient final Logger log = LoggerFactory.getLogger(EmailSendGridNotificationHandler.class);

    private EmailSendGridConfig cfg;
    private SendGrid sendgrid;

    public EmailSendGridNotificationHandler(MxisdConfig mCfg, EmailSendGridConfig cfg) {
        super(mCfg.getMatrix(), mCfg.getServer());
        this.cfg = cfg.build();
        this.sendgrid = new SendGrid(cfg.getApi().getKey());
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getMedium() {
        return ThreePidMedium.Email.getId();
    }

    protected Email getFrom() {
        Email email = new Email();
        email.setEmail(cfg.getIdentity().getFrom());
        email.setName(cfg.getIdentity().getName());
        return email;
    }

    private String getFromFile(String path) {
        try {
            return FileUtil.load(path);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't create notification content using file " + path, e);
        }
    }

    @Override
    public void sendForInvite(IMatrixIdInvite invite) {
        EmailTemplate template = cfg.getTemplates().getGeneric().get("matrixId");
        if (StringUtils.isAllBlank(template.getBody().getText(), template.getBody().getHtml())) {
            throw new FeatureNotAvailable("No template has been configured for Matrix ID invite notifications");
        }

        Mail mail = new Mail();
        mail.setSubject(populateForInvite(invite, template.getSubject()));

        Content plainContent = new Content("text/plain", populateForInvite(invite, getFromFile(template.getBody().getText())));
        Content htmlContent = new Content("text/html", populateForInvite(invite, getFromFile(template.getBody().getHtml())));

        mail.addContent(plainContent);
        mail.addContent(htmlContent);

        send(invite.getAddress(), mail);
    }

    @Override
    public void sendForReply(IThreePidInviteReply invite) {
        EmailTemplate template = cfg.getTemplates().getInvite();
        if (StringUtils.isAllBlank(template.getBody().getText(), template.getBody().getHtml())) {
            throw new FeatureNotAvailable("No template has been configured for 3PID invite notifications");
        }

        Mail mail = new Mail();
        mail.setSubject(populateForReply(invite, template.getSubject()));

        Content plainContent = new Content("text/plain", populateForReply(invite, getFromFile(template.getBody().getText())));
        Content htmlContent = new Content("text/html", populateForReply(invite, getFromFile(template.getBody().getHtml())));

        mail.addContent(plainContent);
        mail.addContent(htmlContent);

        send(invite.getInvite().getAddress(), mail);
    }

    @Override
    public void sendForValidation(IThreePidSession session) {
        EmailTemplate template = cfg.getTemplates().getSession().getValidation();
        if (StringUtils.isAllBlank(template.getBody().getText(), template.getBody().getHtml())) {
            throw new FeatureNotAvailable("No template has been configured for validation notifications");
        }

        Mail mail = new Mail();
        mail.setSubject(populateForValidation(session, template.getSubject()));

        Content plainContent = new Content("text/plain", populateForValidation(session, getFromFile(template.getBody().getText())));
        Content htmlContent = new Content("text/html", populateForValidation(session, getFromFile(template.getBody().getHtml())));

        mail.addContent(plainContent);
        mail.addContent(htmlContent);

        send(session.getThreePid().getAddress(), mail);
    }

    @Override
    public void sendForUnbind(ThreePid tpid) {
        EmailTemplate template = cfg.getTemplates().getSession().getUnbind();
        if (StringUtils.isAllBlank(template.getBody().getText(), template.getBody().getHtml())) {
            throw new FeatureNotAvailable("No template has been configured for unbind notifications");
        }

        Mail mail = new Mail();
        mail.setSubject(populateForCommon(tpid, template.getSubject()));

        Content plainContent = new Content("text/plain", populateForCommon(tpid, getFromFile(template.getBody().getText())));
        Content htmlContent = new Content("text/html", populateForCommon(tpid, getFromFile(template.getBody().getHtml())));

        mail.addContent(plainContent);
        mail.addContent(htmlContent);

        send(tpid.getAddress(), mail);
    }

    private void send(String recipient, Mail mail) {
        if (StringUtils.isBlank(cfg.getIdentity().getFrom())) {
            throw new FeatureNotAvailable("3PID Email identity: sender address is empty - " +
                    "You must set a value for notifications to work");
        }

        try {
            mail.setFrom(getFrom());

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendgrid.api(request);

            if (response.getStatusCode() < 300) {
                log.info("Successfully sent email to {} using SendGrid", recipient);
            } else {
                throw new RuntimeException("Error sending via SendGrid to " + recipient + ": " + response.getStatusCode() + ", " + response.getBody());
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to send e-mail invite via SendGrid to " + recipient, e);
        }
    }

}
