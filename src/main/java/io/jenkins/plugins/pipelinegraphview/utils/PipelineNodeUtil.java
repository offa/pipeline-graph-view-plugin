package io.jenkins.plugins.pipelinegraphview.utils;

import com.google.common.base.Predicate;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Action;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import org.jenkinsci.plugins.pipeline.StageStatus;
import org.jenkinsci.plugins.pipeline.SyntheticStage;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStep;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;

/** @author Vivek Pandey */
public class PipelineNodeUtil {

  private static final String DECLARATIVE_DISPLAY_NAME_PREFIX = "Declarative: ";

  public static String getDisplayName(@NonNull FlowNode node) {
    ThreadNameAction threadNameAction = node.getAction(ThreadNameAction.class);
    String name =
        threadNameAction != null ? threadNameAction.getThreadName() : node.getDisplayName();
    return isSyntheticStage(node) && name.startsWith(DECLARATIVE_DISPLAY_NAME_PREFIX)
        ? name.substring(DECLARATIVE_DISPLAY_NAME_PREFIX.length())
        : name;
  }

  public static boolean isStage(FlowNode node) {
    return node != null
        && ((node.getAction(StageAction.class) != null)
            || (node.getAction(LabelAction.class) != null
                && node.getAction(ThreadNameAction.class) == null));
  }

  public static boolean isSyntheticStage(@Nullable FlowNode node) {
    return node != null && getSyntheticStage(node) != null;
  }

  @CheckForNull
  public static TagsAction getSyntheticStage(@Nullable FlowNode node) {
    if (node != null) {
      for (Action action : node.getActions()) {
        if (action instanceof TagsAction
            && ((TagsAction) action).getTagValue(SyntheticStage.TAG_NAME) != null) {
          return (TagsAction) action;
        }
      }
    }
    return null;
  }

  public static boolean isPostSyntheticStage(@Nullable FlowNode node) {
    if (node == null) {
      return false;
    }
    TagsAction tagsAction = getSyntheticStage(node);
    if (tagsAction == null) {
      return false;
    }
    String value = tagsAction.getTagValue(SyntheticStage.TAG_NAME);
    return value != null && value.equals(SyntheticStage.getPost());
  }

  public static boolean isSkippedStage(@Nullable FlowNode node) {
    if (node == null) {
      return false;
    }

    for (Action action : node.getActions()) {
      if (action instanceof TagsAction
          && ((TagsAction) action).getTagValue(StageStatus.TAG_NAME) != null) {
        TagsAction tagsAction = (TagsAction) action;
        String value = tagsAction.getTagValue(StageStatus.TAG_NAME);
        return value != null
            && (value.equals(StageStatus.getSkippedForConditional())
                || value.equals(StageStatus.getSkippedForFailure())
                || value.equals(StageStatus.getSkippedForUnstable()));
      }
    }

    return false;
  }

  public static boolean isPreSyntheticStage(@Nullable FlowNode node) {
    if (node == null) {
      return false;
    }
    TagsAction tagsAction = getSyntheticStage(node);
    if (tagsAction == null) {
      return false;
    }
    String value = tagsAction.getTagValue(SyntheticStage.TAG_NAME);
    return value != null && value.equals(SyntheticStage.getPre());
  }

  public static boolean isParallelBranch(@Nullable FlowNode node) {
    return node != null
        && node.getAction(LabelAction.class) != null
        && node.getAction(ThreadNameAction.class) != null;
  }

  public static String getArgumentsAsString(@Nullable FlowNode node) {
    if (node != null) {
      return ArgumentsAction.getStepArgumentsAsString(node);
    }
    return "";
  }

  /**
   * Gives cause of block for declarative style plugin where agent (node block) is declared inside a
   * stage.
   *
   * <pre>
   *    pipeline {
   *      agent none
   *      stages {
   *          stage ('first') {
   *              agent {
   *                  label 'first'
   *              }
   *              steps{
   *                  sh 'echo "from first"'
   *              }
   *          }
   *      }
   *    }
   *  </pre>
   *
   * @param stage stage's {@link FlowNode}
   * @param nodeBlock agent or node block's {@link FlowNode}
   * @return cause of block if present, nul otherwise
   */
  public static @CheckForNull String getCauseOfBlockage(
      @NonNull FlowNode stage, @Nullable FlowNode nodeBlock) {
    if (nodeBlock != null) {
      // Check and see if this node block is inside this stage
      for (FlowNode p : nodeBlock.getParents()) {
        if (p.equals(stage)) {
          Queue.Item item = QueueItemAction.getQueueItem(nodeBlock);
          if (item != null) {
            CauseOfBlockage causeOfBlockage = item.getCauseOfBlockage();
            String cause = null;
            if (causeOfBlockage != null) {
              cause = causeOfBlockage.getShortDescription();
              if (cause == null) {
                causeOfBlockage = item.task.getCauseOfBlockage();
                if (causeOfBlockage != null) {
                  return causeOfBlockage.getShortDescription();
                }
              }
            }
            return cause;
          }
        }
      }
    }
    return null;
  }

  public static final Predicate<FlowNode> isLoggable =
      input -> {
        if (input == null) return false;
        return input.getAction(LogAction.class) != null;
      };

  public static boolean isPausedForInputStep(
      @NonNull StepAtomNode step, @Nullable InputAction inputAction) {
    if (inputAction == null) {
      return false;
    }
    PauseAction pauseAction = step.getAction(PauseAction.class);
    return (pauseAction != null
        && pauseAction.isPaused()
        && pauseAction.getCause().equals("Input"));
  }

  /**
   * Determine if the given {@link FlowNode} is the initial {@link StepStartNode} for an {@link
   * ExecutorStep}.
   *
   * @param node a possibly null {@link FlowNode}
   * @return true if {@code node} is the non-body start of the agent execution.
   */
  public static boolean isAgentStart(@Nullable FlowNode node) {
    if (node != null) {
      if (node instanceof StepStartNode) {
        StepStartNode stepStartNode = (StepStartNode) node;
        if (stepStartNode.getDescriptor() != null) {
          StepDescriptor sd = stepStartNode.getDescriptor();
          return sd != null
              && ExecutorStep.DescriptorImpl.class.equals(sd.getClass())
              && !stepStartNode.isBody();
        }
      }
    }

    return false;
  }
}
