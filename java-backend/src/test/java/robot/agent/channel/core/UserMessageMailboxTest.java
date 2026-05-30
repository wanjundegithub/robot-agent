package robot.agent.channel.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMessageMailboxTest {

    @Test
    void drainsMessagesInFifoOrder() {
        UserMessageMailbox mailbox = new UserMessageMailbox(2);
        UserMessage first = new UserMessage(null, null, null, null);
        UserMessage second = new UserMessage(null, null, null, null);

        assertThat(mailbox.offer(first)).isTrue();
        assertThat(mailbox.offer(second)).isTrue();

        assertThat(mailbox.poll()).isSameAs(first);
        assertThat(mailbox.poll()).isSameAs(second);
        assertThat(mailbox.poll()).isNull();
    }

    @Test
    void rejectsMessagesWhenQueueIsFull() {
        UserMessageMailbox mailbox = new UserMessageMailbox(1);

        assertThat(mailbox.offer(new UserMessage(null, null, null, null))).isTrue();
        assertThat(mailbox.offer(new UserMessage(null, null, null, null))).isFalse();
    }
}
